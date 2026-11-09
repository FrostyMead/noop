package com.noop.analytics

import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Health Connect band owns a day but carries no RR, so its Charge comes from its own daily HRV + resting HR
 * ([IntelligenceEngine.summaryRecoveryFills]). These pin WHICH days are filled and that the fill tracks the
 * band's input rather than returning one fixed value.
 */
class SummaryRecoveryFillsTest {

    private val band = "hc-com.example.band"

    private fun day(i: Int) = "2026-03-%02d".format(i)

    /** 20 steady nights of band vitals, then [last] overriding the final night's HRV / RHR. */
    private fun bandHistory(lastHrv: Double, lastRhr: Int): List<DailyMetric> =
        (1..20).map { i ->
            val hrv = if (i == 20) lastHrv else 60.0 + (i % 3)   // a little natural wobble around 60 ms
            val rhr = if (i == 20) lastRhr else 52 + (i % 2)
            DailyMetric(deviceId = band, day = day(i), avgHrv = hrv, restingHr = rhr)
        }

    private fun scoredRow(i: Int, recovery: Double? = null) =
        DailyMetric(deviceId = "my-whoop-noop", day = day(i), recovery = recovery, strain = 40.0)

    @Test fun fillsOnlyTheBandsOwnUnscoredDays() {
        val scored = listOf(scoredRow(18), scoredRow(19), scoredRow(20, recovery = 77.0))
        val owners = mapOf(day(18) to band, day(19) to "my-whoop", day(20) to band)
        val fills = IntelligenceEngine.summaryRecoveryFills(scored, owners, band, bandHistory(61.0, 52))
        assertTrue(day(18) in fills)                 // the band owned it and nothing scored it
        assertFalse(day(19) in fills)                // another device owned it
        assertFalse(day(20) in fills)                // already has a Charge: never overwritten
    }

    @Test fun calibratesBeforeEnoughHistory() {
        val short = (1..4).map { DailyMetric(deviceId = band, day = day(it), avgHrv = 60.0, restingHr = 52) }
        val fills = IntelligenceEngine.summaryRecoveryFills(listOf(scoredRow(4)), mapOf(day(4) to band), band, short)
        assertTrue("no number before the baseline is usable", fills.isEmpty())
    }

    @Test fun chargeTracksTheBandsNight() {
        val owners = mapOf(day(20) to band)
        val scored = listOf(scoredRow(20))
        // Several different nights against the same history: a higher HRV / lower RHR night must score
        // higher, and the scores must differ, i.e. the fill follows the input rather than a constant.
        val nights = listOf(50.0 to 57, 56.0 to 54, 60.0 to 53, 64.0 to 52, 70.0 to 50)
        val charges = nights.map { (hrv, rhr) ->
            IntelligenceEngine.summaryRecoveryFills(scored, owners, band, bandHistory(hrv, rhr))[day(20)]
        }
        charges.forEach { assertNotNull(it) }
        val values = charges.map { it!! }
        assertEquals("monotone in the night's readings", values.sorted(), values)
        assertTrue("distinct inputs give distinct scores", values.toSet().size == values.size)
    }

    @Test fun noBandRowsNoFills() {
        val fills = IntelligenceEngine.summaryRecoveryFills(listOf(scoredRow(20)), mapOf(day(20) to band), band, emptyList())
        assertTrue(fills.isEmpty())
    }
}
