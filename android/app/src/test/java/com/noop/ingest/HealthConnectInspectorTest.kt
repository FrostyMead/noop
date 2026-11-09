package com.noop.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class HealthConnectInspectorTest {

    private val utc = ZoneOffset.UTC

    @Test fun typicalGapRecoversSeveralCadences() {
        // Different bands write at different rates; the median must recover each, not one fixed value.
        for (cadence in listOf(1L, 30L, 60L, 120L, 300L)) {
            val times = (0 until 200).map { 1_700_000_000L + it * cadence }
            assertEquals("cadence $cadence s", cadence, HealthConnectInspector.typicalGapSec(times))
        }
    }

    @Test fun offWristGapsAreNotCadence() {
        // A minute-cadence day with two long off-wrist holes still reads as one minute.
        val worn = (0 until 120).map { it * 60L } + (0 until 120).map { 20_000L + it * 60L } +
            (0 until 120).map { 60_000L + it * 60L }
        assertEquals(60L, HealthConnectInspector.typicalGapSec(worn.shuffled()))
    }

    @Test fun tooFewReadingsHaveNoCadence() {
        assertNull(HealthConnectInspector.typicalGapSec(emptyList()))
        assertNull(HealthConnectInspector.typicalGapSec(listOf(5L)))
        assertNull(HealthConnectInspector.typicalGapSec(listOf(5L, 5L)))   // duplicates are one reading
        assertNull(HealthConnectInspector.typicalGapSec(listOf(0L, 10_000L)))   // only an off-wrist gap
    }

    @Test fun accumulatorCountsPerSourceAndDay() {
        val acc = HealthConnectInspector.HcSourceAccumulator("com.example.band", utc)
        val day0 = 1_700_006_400L   // 2023-11-15T00:00:00Z
        (0 until 300).forEach { acc.addHeartRate(day0 + it * 60L) }
        (0 until 300).forEach { acc.addHeartRate(day0 + 86_400L + it * 60L) }
        acc.addHeartRate(day0)   // duplicate timestamp: not a second reading
        acc.addSleep(stageCount = 12)
        acc.addSleep(stageCount = 0)
        acc.addHrv(); acc.addRestingHr(); acc.addSteps()
        val s = acc.summary()
        assertEquals(600, s.hrSamples)
        assertEquals(2, s.hrDays)
        assertEquals(60L, s.hrTypicalGapSec)
        assertEquals(2, s.sleepSessions)
        assertEquals(1, s.sleepWithStages)
        assertEquals(605, s.total)
    }

    @Test fun readinessFollowsTheEvidence() {
        fun summary(gap: Long?, stages: Int, hrv: Int, rhr: Int) = HealthConnectInspector.SourceSummary(
            packageName = "p", hrSamples = 500, hrTypicalGapSec = gap, hrDays = 3,
            sleepSessions = stages, sleepWithStages = stages, hrvReadings = hrv, restingHrReadings = rhr,
            spo2Readings = 0, respReadings = 0, stepRecords = 0,
        )
        val minuteBand = HealthConnectInspector.readiness(summary(60, 3, 3, 3))
        assertTrue(minuteBand.effort && minuteBand.sleep && minuteBand.recovery)
        // Sparser than the Effort scorer credits per reading: not ready, rather than silently under-counted.
        assertFalse(HealthConnectInspector.readiness(summary(300, 3, 3, 3)).effort)
        assertTrue(HealthConnectInspector.readiness(summary(120, 3, 3, 3)).effort)
        assertFalse(HealthConnectInspector.readiness(summary(null, 3, 3, 3)).effort)
        assertFalse(HealthConnectInspector.readiness(summary(60, 0, 3, 3)).sleep)
        assertFalse(HealthConnectInspector.readiness(summary(60, 3, 3, 0)).recovery)
    }

    @Test fun knownAppsGetNamesOthersKeepTheirPackage() {
        assertEquals("Garmin Connect", HealthConnectInspector.appName("com.garmin.android.apps.connectmobile"))
        assertEquals("com.example.band", HealthConnectInspector.appName("com.example.band"))
    }
}
