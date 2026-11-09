package com.noop.ingest

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.noop.data.DeviceRegistry
import com.noop.data.DeviceStatus
import com.noop.data.HrSample
import com.noop.data.Metric
import com.noop.ui.NoopPrefs
import com.noop.data.PairedDeviceRow
import com.noop.data.SleepSession
import com.noop.data.SourceKind
import com.noop.data.WhoopRepository
import java.time.Instant
import kotlin.math.max

/**
 * Phase 1 of "any band via Health Connect" (docs/HEALTH_CONNECT_BAND.md): a band whose own phone app
 * writes to Health Connect becomes a registered device, `hc-<package>`, holding that app's heart-rate
 * stream and sleep sessions (with their stage timeline) under its own id. Nothing is written under a
 * WHOOP id, so a strap's rows are never touched.
 *
 * The mapping rules are pure and unit-tested; [sync] is the only Health Connect read.
 */
object HealthConnectBand {

    const val ID_PREFIX = "hc"
    const val MODEL = "Health Connect"

    /** How far back the first sync reaches. */
    const val FIRST_SYNC_DAYS: Long = 30

    /** Each later sync re-reads this much before the last one, for late-arriving watch uploads. Inserts
     *  are keyed (deviceId, ts), so the overlap never duplicates. */
    const val RESYNC_OVERLAP_SEC: Long = 2 * 86_400L

    /** Readings outside this band are sensor noise, not a heart rate (the generic-strap range). */
    val PLAUSIBLE_BPM = 30..220

    fun deviceId(packageName: String): String = "$ID_PREFIX-$packageName"

    fun packageOf(row: PairedDeviceRow): String? =
        if (isBand(row) && row.id.startsWith("$ID_PREFIX-")) row.id.removePrefix("$ID_PREFIX-") else null

    fun isBand(row: PairedDeviceRow): Boolean = row.sourceKind == SourceKind.healthConnect.name

    /** Start of the window the next sync reads, epoch seconds. */
    fun syncStart(lastSyncSec: Long?, nowSec: Long): Long {
        val firstSync = nowSec - FIRST_SYNC_DAYS * 86_400L
        return if (lastSyncSec == null) firstSync else max(firstSync, lastSyncSec - RESYNC_OVERLAP_SEC)
    }

    /** HR samples as stored rows: plausible readings only, one per second (the table's key). */
    fun hrRows(deviceId: String, samples: List<Pair<Long, Long>>): List<HrSample> =
        samples.asSequence()
            .filter { (_, bpm) -> bpm in PLAUSIBLE_BPM.first.toLong()..PLAUSIBLE_BPM.last.toLong() }
            .distinctBy { it.first }
            .sortedBy { it.first }
            .map { (ts, bpm) -> HrSample(deviceId = deviceId, ts = ts, bpm = bpm.toInt()) }
            .toList()

    /** One stage span of a Health Connect sleep session: its stage-type constant and bounds (epoch s). */
    data class StageSpan(val type: Int, val start: Long, val end: Long)

    /** The analyzer's stage vocabulary for a Health Connect stage type; null for a type with no stage
     *  meaning (generic "sleeping", unknown), which is left out rather than guessed. */
    fun stageName(type: Int): String? = when (type) {
        SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
        SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
        SleepSessionRecord.STAGE_TYPE_REM -> "rem"
        SleepSessionRecord.STAGE_TYPE_AWAKE,
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "wake"
        else -> null
    }

    /**
     * The `[{stage,start,end}]` timeline [com.noop.analytics.AnalyticsEngine.decodeStages] reads (the
     * Oura hypnogram shape), so the night can be handed to the analyzer as provided sleep. Null when no
     * span carries a real stage, so a duration-only night is never given an invented hypnogram.
     */
    fun stageTimelineJson(spans: List<StageSpan>): String? {
        val arr = org.json.JSONArray()
        spans.sortedBy { it.start }.forEach { s ->
            val name = stageName(s.type) ?: return@forEach
            if (s.end <= s.start) return@forEach
            arr.put(org.json.JSONObject().put("stage", name).put("start", s.start).put("end", s.end))
        }
        return if (arr.length() == 0) null else arr.toString()
    }

    fun sleepSession(deviceId: String, start: Long, end: Long, spans: List<StageSpan>): SleepSession =
        SleepSession(deviceId = deviceId, startTs = start, endTs = end, stagesJSON = stageTimelineJson(spans))

    /** The metrics the inspector saw this app provide: claimed only when at least one reading arrived. */
    fun capabilities(s: HealthConnectInspector.SourceSummary): String = buildList {
        if (s.hrSamples > 0) add(Metric.hr.name)
        if (s.hrvReadings > 0) add(Metric.hrv.name)
        if (s.sleepSessions > 0) add(Metric.sleep.name)
        if (s.spo2Readings > 0) add(Metric.spo2.name)
        if (s.stepRecords > 0) add(Metric.steps.name)
    }.joinToString(",")

    /** The registry row for an app the inspector found. Never active: a band is not a BLE source. */
    fun deviceRow(s: HealthConnectInspector.SourceSummary, nowSec: Long, existing: PairedDeviceRow? = null) =
        PairedDeviceRow(
            id = deviceId(s.packageName),
            brand = HealthConnectInspector.appName(s.packageName),
            model = MODEL,
            nickname = existing?.nickname,
            peripheralId = null,
            sourceKind = SourceKind.healthConnect.name,
            capabilities = capabilities(s),
            status = DeviceStatus.paired.name,
            addedAt = existing?.addedAt ?: nowSec,
            lastSeenAt = nowSec,
        )

    /** Counts from one sync, for the status line and the log. */
    data class SyncResult(val hrRows: Int, val nights: Int)

    /**
     * Reads [row]'s app's heart rate and sleep since the last sync (see [syncStart]) and stores them under
     * the band's id. Null when Health Connect is unavailable or neither type is granted; a type that fails
     * to read is skipped, as on import.
     */
    suspend fun sync(context: Context, repo: WhoopRepository, registry: DeviceRegistry, row: PairedDeviceRow): SyncResult? {
        val pkg = packageOf(row) ?: return null
        if (HealthConnectImporter.sdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return null
        val client = HealthConnectImporter.client(context)
        val granted = runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
        val canHr = HealthPermission.getReadPermission(HeartRateRecord::class) in granted
        val canSleep = HealthPermission.getReadPermission(SleepSessionRecord::class) in granted
        if (!canHr && !canSleep) return null

        val now = Instant.now().epochSecond
        val filter = TimeRangeFilter.between(Instant.ofEpochSecond(syncStart(HealthConnectBandPrefs.lastSync(context, row.id), now)), Instant.ofEpochSecond(now))
        val origin = setOf(DataOrigin(pkg))

        var hrStored = 0
        if (canHr) {
            val samples = ArrayList<Pair<Long, Long>>()
            runCatching {
                var token: String? = null
                do {
                    val page = client.readRecords(ReadRecordsRequest(HeartRateRecord::class, filter, origin, pageSize = 1000, pageToken = token))
                    page.records.forEach { r -> r.samples.forEach { samples += it.time.epochSecond to it.beatsPerMinute } }
                    token = page.pageToken
                } while (token != null)
            }
            hrRows(row.id, samples).chunked(5_000).forEach { hrStored += repo.insertHr(it).count { id -> id != -1L } }
        }

        val nights = ArrayList<SleepSession>()
        if (canSleep) {
            runCatching {
                var token: String? = null
                do {
                    val page = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, filter, origin, pageSize = 1000, pageToken = token))
                    page.records.forEach { r ->
                        nights += sleepSession(row.id, r.startTime.epochSecond, r.endTime.epochSecond,
                            r.stages.map { StageSpan(it.stage, it.startTime.epochSecond, it.endTime.epochSecond) })
                    }
                    token = page.pageToken
                } while (token != null)
            }
            if (nights.isNotEmpty()) repo.upsertSleepSessions(nights)
        }

        registry.touchLastSeen(row.id, now)
        HealthConnectBandPrefs.setLastSync(context, row.id, now)
        return SyncResult(hrRows = hrStored, nights = nights.size)
    }
}

/**
 * Per-device sync bookkeeping and the experimental scoring switch. Scoring is OFF by default
 * (`CLAUDE.md`: a new source feeds no score until it is validated on real data); while off, the
 * day-owner resolver never picks a band, so its rows are stored but score nothing.
 */
object HealthConnectBandPrefs {
    private const val KEY_SCORING = "hcBand.scoring"
    private fun lastSyncKey(id: String) = "hcBand.lastSync.$id"

    /** Read by [com.noop.analytics.RegistryDayOwnerSource] on every scoring pass; set by [load]/[setScoring]. */
    @Volatile var scoringEnabled: Boolean = false
        private set

    fun load(context: Context) {
        scoringEnabled = NoopPrefs.of(context).getBoolean(KEY_SCORING, false)
    }

    fun setScoring(context: Context, on: Boolean) {
        scoringEnabled = on
        NoopPrefs.of(context).edit().putBoolean(KEY_SCORING, on).apply()
    }

    fun lastSync(context: Context, id: String): Long? =
        NoopPrefs.of(context).getLong(lastSyncKey(id), 0L).takeIf { it > 0L }

    fun setLastSync(context: Context, id: String, sec: Long) {
        NoopPrefs.of(context).edit().putLong(lastSyncKey(id), sec).apply()
    }
}
