package com.noop.ingest

import androidx.health.connect.client.records.SleepSessionRecord
import com.noop.analytics.AnalyticsEngine
import com.noop.data.DeviceStatus
import com.noop.data.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthConnectBandTest {

    private fun summary(hr: Int = 0, sleep: Int = 0, hrv: Int = 0, spo2: Int = 0, steps: Int = 0) =
        HealthConnectInspector.SourceSummary(
            packageName = "com.garmin.android.apps.connectmobile", hrSamples = hr, hrTypicalGapSec = 60, hrDays = 1,
            sleepSessions = sleep, sleepWithStages = sleep, hrvReadings = hrv, restingHrReadings = hrv,
            spo2Readings = spo2, respReadings = 0, stepRecords = steps,
        )

    @Test fun deviceIdIsTheBandsOwnNamespace() {
        val row = HealthConnectBand.deviceRow(summary(hr = 10), nowSec = 1_000)
        assertEquals("hc-com.garmin.android.apps.connectmobile", row.id)
        assertEquals("com.garmin.android.apps.connectmobile", HealthConnectBand.packageOf(row))
        assertEquals(SourceKind.healthConnect.name, row.sourceKind)
        // Never active: a band is read from its phone app, not streamed.
        assertEquals(DeviceStatus.paired.name, row.status)
        assertEquals("Garmin Connect", row.brand)
    }

    @Test fun rerunKeepsNicknameAndPairingDate() {
        val first = HealthConnectBand.deviceRow(summary(hr = 10), nowSec = 1_000).copy(nickname = "Forerunner")
        val again = HealthConnectBand.deviceRow(summary(hr = 10, sleep = 2), nowSec = 9_000, existing = first)
        assertEquals("Forerunner", again.nickname)
        assertEquals(1_000L, again.addedAt)
        assertEquals(9_000L, again.lastSeenAt)
    }

    @Test fun capabilitiesClaimOnlyWhatArrived() {
        assertEquals("hr", HealthConnectBand.capabilities(summary(hr = 5)))
        assertEquals("hr,hrv,sleep,spo2,steps", HealthConnectBand.capabilities(summary(5, 1, 1, 1, 1)))
        assertEquals("", HealthConnectBand.capabilities(summary()))
    }

    @Test fun syncWindowFirstThenOverlapping() {
        val now = 100L * 86_400L
        assertEquals(now - 30 * 86_400L, HealthConnectBand.syncStart(null, now))
        val last = now - 3_600L
        assertEquals(last - 2 * 86_400L, HealthConnectBand.syncStart(last, now))
        // A very old last sync never reaches further back than a first sync would.
        assertEquals(now - 30 * 86_400L, HealthConnectBand.syncStart(1L, now))
    }

    @Test fun hrRowsDropNoiseAndDuplicateSeconds() {
        val rows = HealthConnectBand.hrRows("hc-x", listOf(120L to 61L, 60L to 60L, 60L to 99L, 180L to 0L, 240L to 250L, 300L to 30L))
        assertEquals(listOf(60L, 120L, 300L), rows.map { it.ts })
        assertTrue(rows.all { it.deviceId == "hc-x" })
        assertEquals(60, rows.first().bpm)   // the first reading for a second wins
    }

    @Test fun stageTimelineIsWhatTheAnalyzerReads() {
        val spans = listOf(
            HealthConnectBand.StageSpan(SleepSessionRecord.STAGE_TYPE_DEEP, 1_000, 2_000),
            HealthConnectBand.StageSpan(SleepSessionRecord.STAGE_TYPE_LIGHT, 0, 1_000),
            HealthConnectBand.StageSpan(SleepSessionRecord.STAGE_TYPE_REM, 2_000, 2_600),
            HealthConnectBand.StageSpan(SleepSessionRecord.STAGE_TYPE_AWAKE, 2_600, 2_700),
            HealthConnectBand.StageSpan(SleepSessionRecord.STAGE_TYPE_SLEEPING, 2_700, 2_800),   // no stage meaning
        )
        val json = HealthConnectBand.stageTimelineJson(spans)
        val decoded = AnalyticsEngine.decodeStages(json)
        assertEquals(listOf("light", "deep", "rem", "wake"), decoded.map { it.stage })
        assertEquals(listOf(0L, 1_000L, 2_000L, 2_600L), decoded.map { it.start })
        val night = HealthConnectBand.sleepSession("hc-x", 0, 2_800, spans)
        assertNotNull(AnalyticsEngine.sleepSessionFromProvided(night))
    }

    @Test fun durationOnlyNightGetsNoInventedHypnogram() {
        val generic = listOf(HealthConnectBand.StageSpan(SleepSessionRecord.STAGE_TYPE_SLEEPING, 0, 28_800))
        assertNull(HealthConnectBand.stageTimelineJson(generic))
        assertNull(HealthConnectBand.stageTimelineJson(emptyList()))
        assertNull(AnalyticsEngine.sleepSessionFromProvided(HealthConnectBand.sleepSession("hc-x", 0, 28_800, generic)))
    }

    @Test fun dailyVitalsAverageEachDayAndDropNoise() {
        val rows = HealthConnectBand.dailyVitals(
            "hc-x",
            hrvByDay = listOf("2026-03-02" to 60.0, "2026-03-02" to 70.0, "2026-03-01" to 0.0, "2026-03-01" to 55.0),
            rhrByDay = listOf("2026-03-02" to 50L, "2026-03-02" to 53L, "2026-03-03" to 300L),
        )
        assertEquals(listOf("2026-03-01", "2026-03-02"), rows.map { it.day })
        assertEquals(55.0, rows[0].avgHrv!!, 0.0)
        assertNull(rows[0].restingHr)
        assertEquals(65.0, rows[1].avgHrv!!, 0.0)
        assertEquals(52, rows[1].restingHr)      // 51.5 rounds up
        assertNull(rows[1].avgSdnn)              // RMSSD is never relabelled as SDNN
        assertTrue(rows.all { it.deviceId == "hc-x" })
    }

    @Test fun syncStartsOnALocalDayBoundary() {
        val zone = java.time.ZoneId.of("Europe/London")
        val midday = java.time.ZonedDateTime.of(2026, 3, 2, 12, 30, 0, 0, zone).toEpochSecond()
        val midnight = java.time.ZonedDateTime.of(2026, 3, 2, 0, 0, 0, 0, zone).toEpochSecond()
        assertEquals(midnight, HealthConnectBand.dayStart(midday, zone))
        assertEquals(midnight, HealthConnectBand.dayStart(midnight, zone))
    }
}
