package com.noop.ui

import com.noop.analytics.VitalBands
import com.noop.data.DailyMetric
import org.junit.Assert.*
import org.junit.Test

class TodayHealthMonitorTest {
    private fun result(band: VitalBands.Band, personal: Boolean = true) = VitalBands.Result(
        band, if (personal) VitalBands.Basis.PERSONAL else VitalBands.Basis.POPULATION, 20)
    private val normal = result(VitalBands.Band.IN_RANGE)
    private val missing = result(VitalBands.Band.NO_DATA)

    @Test fun partialDataNeverClaimsAllReadingsAreNormal() {
        assertEquals(TodayMonitorStatus.PARTIAL, todayMonitorStatus(listOf(normal, normal, missing)))
        assertEquals(TodayMonitorStatus.EMPTY, todayMonitorStatus(listOf(missing, missing, missing)))
        assertEquals(TodayMonitorStatus.EMPTY, todayMonitorStatus(emptyList()))
    }

    @Test fun warningsRemainVisibleEvenWhenOtherReadingsAreMissing() {
        assertEquals(TodayMonitorStatus.OUTSIDE,
            todayMonitorStatus(listOf(normal, result(VitalBands.Band.OUT_OF_RANGE), missing)))
    }

    @Test fun populationFallbackIsNotPresentedAsPersonalBaseline() {
        assertEquals(TodayMonitorStatus.TYPICAL, todayMonitorStatus(listOf(normal, normal,
            result(VitalBands.Band.IN_RANGE, personal = false))))
        assertEquals(TodayMonitorStatus.PERSONAL, todayMonitorStatus(listOf(normal, normal, normal)))
    }

    @Test fun carryIsResolvedPerMetricWithoutRelabellingTheReadingDate() {
        val selected = DailyMetric("test", "2026-09-06", avgHrv = 71.0)
        val prior = DailyMetric("test", "2026-09-05", avgHrv = 60.0, restingHr = 55)
        val respiratory = DailyMetric("test", "2026-09-04", respRateBpm = 14.2)
        val sources = todayMonitorSources(selected, prior, respiratory).toMap()
        assertEquals(selected, sources["hrv"])
        assertEquals(prior, sources["rhr"])
        assertEquals(respiratory, sources["resp"])
    }

    @Test fun historicalDayDoesNotInventACarry() {
        val selected = DailyMetric("test", "2026-08-01", restingHr = 58)
        val sources = todayMonitorSources(selected, null, null).toMap()
        assertNull(sources["hrv"])
        assertNull(sources["resp"])
        assertEquals(selected, sources["rhr"])
    }
}
