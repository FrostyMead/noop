package com.noop.ingest

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.ZoneId
import kotlin.reflect.KClass

/**
 * Phase 0 of "any band via Health Connect" (docs/HEALTH_CONNECT_BAND.md): a read-only look at what each
 * app writing to Health Connect actually provides, so a band's suitability is measured on the user's own
 * data before anything is built on it. Nothing is stored.
 *
 * The summarising is pure ([HcSourceAccumulator], [typicalGapSec], [readiness]) and unit-tested; only
 * [inspect] touches Health Connect.
 */
object HealthConnectInspector {

    /** How far back the inspector looks. A week covers several nights and a full weekday/weekend cycle. */
    const val WINDOW_DAYS: Long = 7

    /** A gap longer than this is the band being off the wrist (or charging), not its sampling cadence. */
    const val WORN_GAP_LIMIT_SEC: Long = 30 * 60

    /** Effort needs a reading at least this often: [com.noop.analytics.StrainScorer.maxSampleGapMin]. */
    const val EFFORT_MAX_GAP_SEC: Long = 120

    /** Friendly names for common band apps; anything else shows its package name. Identification only. */
    private val KNOWN_APPS = mapOf(
        "com.garmin.android.apps.connectmobile" to "Garmin Connect",
        "com.huami.watch.hmwatchmanager" to "Zepp",
        "com.xiaomi.hm.health" to "Zepp Life",
        "com.xiaomi.wearable" to "Mi Fitness",
        "com.mi.health" to "Mi Fitness",
        "com.sec.android.app.shealth" to "Samsung Health",
        "com.fitbit.FitbitMobile" to "Fitbit",
        "com.google.android.apps.fitness" to "Google Fit",
        "com.ouraring.oura" to "Oura",
        "com.withings.wiscale2" to "Withings",
        "fi.polar.polarflow" to "Polar Flow",
        "com.huawei.health" to "Huawei Health",
    )

    fun appName(packageName: String): String = KNOWN_APPS[packageName] ?: packageName

    /** What one source app wrote over the window. */
    data class SourceSummary(
        val packageName: String,
        val hrSamples: Int,
        /** Median spacing of worn HR readings, seconds; null with fewer than two readings. */
        val hrTypicalGapSec: Long?,
        val hrDays: Int,
        val sleepSessions: Int,
        val sleepWithStages: Int,
        val hrvReadings: Int,
        val restingHrReadings: Int,
        val spo2Readings: Int,
        val respReadings: Int,
        val stepRecords: Int,
    ) {
        val total: Int
            get() = hrSamples + sleepSessions + hrvReadings + restingHrReadings + spo2Readings +
                respReadings + stepRecords
    }

    /** Which NOOP scores this source's data could carry, on the evidence of the window. */
    data class Readiness(val effort: Boolean, val sleep: Boolean, val recovery: Boolean)

    fun readiness(s: SourceSummary): Readiness = Readiness(
        effort = s.hrTypicalGapSec != null && s.hrTypicalGapSec <= EFFORT_MAX_GAP_SEC && s.hrDays >= 1,
        sleep = s.sleepWithStages > 0,
        recovery = s.hrvReadings > 0 && s.restingHrReadings > 0,
    )

    /**
     * The median gap between consecutive readings, ignoring gaps longer than [WORN_GAP_LIMIT_SEC] (time
     * off the wrist is not cadence). Order-insensitive; duplicate timestamps are ignored. Null when there
     * are fewer than two distinct worn readings.
     */
    fun typicalGapSec(epochSecs: Collection<Long>): Long? {
        val sorted = epochSecs.toSortedSet().toList()
        if (sorted.size < 2) return null
        val gaps = (1 until sorted.size)
            .map { sorted[it] - sorted[it - 1] }
            .filter { it in 1..WORN_GAP_LIMIT_SEC }
            .sorted()
        if (gaps.isEmpty()) return null
        val mid = gaps.size / 2
        return if (gaps.size % 2 == 1) gaps[mid] else (gaps[mid - 1] + gaps[mid]) / 2
    }

    /** Collects one app's records; [summary] reduces them. Pure, so the counting rules are testable. */
    class HcSourceAccumulator(val packageName: String, private val zone: ZoneId) {
        private val hrTimes = HashSet<Long>()
        private val hrDays = HashSet<String>()
        var sleepSessions = 0; private set
        var sleepWithStages = 0; private set
        var hrv = 0; private set
        var restingHr = 0; private set
        var spo2 = 0; private set
        var resp = 0; private set
        var steps = 0; private set

        fun addHeartRate(epochSec: Long) {
            hrTimes += epochSec
            hrDays += Instant.ofEpochSecond(epochSec).atZone(zone).toLocalDate().toString()
        }

        /** [stageCount] is the number of stage segments the session carries (0 = duration only). */
        fun addSleep(stageCount: Int) {
            sleepSessions++
            if (stageCount > 0) sleepWithStages++
        }

        fun addHrv() { hrv++ }
        fun addRestingHr() { restingHr++ }
        fun addSpo2() { spo2++ }
        fun addResp() { resp++ }
        fun addSteps() { steps++ }

        fun summary(): SourceSummary = SourceSummary(
            packageName = packageName,
            hrSamples = hrTimes.size,
            hrTypicalGapSec = typicalGapSec(hrTimes),
            hrDays = hrDays.size,
            sleepSessions = sleepSessions,
            sleepWithStages = sleepWithStages,
            hrvReadings = hrv,
            restingHrReadings = restingHr,
            spo2Readings = spo2,
            respReadings = resp,
            stepRecords = steps,
        )
    }

    /**
     * Reads the last [WINDOW_DAYS] of the record types the user granted and summarises them per source
     * app, busiest first. NOOP's own writes are skipped, as on import. A type that is not granted or
     * fails to read is simply absent. Returns null when Health Connect is unavailable.
     */
    suspend fun inspect(context: Context): List<SourceSummary>? {
        if (HealthConnectImporter.sdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return null
        val client = HealthConnectImporter.client(context)
        val granted = runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
        val zone = ZoneId.systemDefault()
        val end = Instant.now()
        val filter = TimeRangeFilter.between(end.minusSeconds(WINDOW_DAYS * 86_400L), end)
        val self = context.packageName
        val bySource = LinkedHashMap<String, HcSourceAccumulator>()
        fun acc(pkg: String) = bySource.getOrPut(pkg) { HcSourceAccumulator(pkg, zone) }

        suspend fun <T : Record> each(type: KClass<T>, onRecord: (String, T) -> Unit) {
            if (HealthPermission.getReadPermission(type) !in granted) return
            var token: String? = null
            runCatching {
                do {
                    val page = client.readRecords(
                        ReadRecordsRequest(recordType = type, timeRangeFilter = filter, pageSize = 1000, pageToken = token),
                    )
                    for (r in page.records) {
                        val pkg = r.metadata.dataOrigin.packageName
                        if (HealthConnectImporter.isSelfWritten(pkg, self)) continue
                        onRecord(pkg, r)
                    }
                    token = page.pageToken
                } while (token != null)
            }
        }

        each(HeartRateRecord::class) { pkg, r -> r.samples.forEach { acc(pkg).addHeartRate(it.time.epochSecond) } }
        each(SleepSessionRecord::class) { pkg, r -> acc(pkg).addSleep(r.stages.size) }
        each(HeartRateVariabilityRmssdRecord::class) { pkg, _ -> acc(pkg).addHrv() }
        each(RestingHeartRateRecord::class) { pkg, _ -> acc(pkg).addRestingHr() }
        each(OxygenSaturationRecord::class) { pkg, _ -> acc(pkg).addSpo2() }
        each(RespiratoryRateRecord::class) { pkg, _ -> acc(pkg).addResp() }
        each(StepsRecord::class) { pkg, _ -> acc(pkg).addSteps() }

        return bySource.values.map { it.summary() }.filter { it.total > 0 }.sortedByDescending { it.total }
    }
}
