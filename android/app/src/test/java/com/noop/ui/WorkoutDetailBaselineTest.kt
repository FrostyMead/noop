package com.noop.ui

import com.noop.data.WorkoutRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The workout detail page's baseline maths — the part of that screen that is a computation rather than a
 * layout, and the part that can quietly lie.
 *
 * Three behaviours are pinned, all of them about NOT inventing a comparison:
 *
 *  1. A baseline needs enough same-sport sessions before it says anything.
 *  2. The peer window is same-sport, strictly-prior, and bounded to 30 days.
 *  3. A typical-range band is null unless the sample supports one, and always lands inside 0..1 —
 *     [TypicalRangeBar] draws the range as dashed markers across the track, so a band outside that
 *     interval would render off the end of the bar.
 */
class WorkoutDetailBaselineTest {

    private fun row(
        startTs: Long,
        sport: String = "jiu jitsu",
        strain: Double? = null,
        kcal: Double? = null,
        device: String = "strap",
    ) = WorkoutRow(
        deviceId = device,
        startTs = startTs,
        endTs = startTs + 3600,
        sport = sport,
        source = "whoop",
        durationS = 3600.0,
        energyKcal = kcal,
        strain = strain,
    )

    // MARK: - sessionBaseline

    @Test fun baseline_needsThreeSessions() {
        assertNull("two samples is not a baseline", sessionBaseline(listOf(10.0, 12.0)))
        assertNotNull("three samples is", sessionBaseline(listOf(10.0, 12.0, 14.0)))
    }

    @Test fun baseline_isTheMean() {
        assertEquals(12.0, sessionBaseline(listOf(10.0, 12.0, 14.0))!!, 1e-9)
    }

    @Test fun baseline_emptyIsNull() {
        assertNull(sessionBaseline(emptyList()))
    }

    // MARK: - baselinePeers

    @Test fun peers_onlySameSport() {
        val subject = row(10_000_000)
        val all = listOf(
            row(9_000_000, sport = "jiu jitsu"),
            row(9_100_000, sport = "running"),
            row(9_200_000, sport = "JIU JITSU"), // case-insensitive: the same sport, differently spelled
            subject,
        )
        val peers = baselinePeers(all, subject)
        assertEquals(2, peers.size)
        assertTrue(peers.all { it.sport.equals("jiu jitsu", ignoreCase = true) })
    }

    @Test fun peers_excludeTheSessionItselfAndAnythingAfterIt() {
        val subject = row(10_000_000)
        val all = listOf(row(9_000_000), subject, row(11_000_000))
        val peers = baselinePeers(all, subject)
        // A later session cannot inform "how does this one compare to what came before".
        assertEquals(1, peers.size)
        assertEquals(9_000_000L, peers.first().startTs)
    }

    @Test fun peers_boundedToThirtyDays() {
        val subject = row(10_000_000)
        val thirtyOneDays = 31L * 24 * 60 * 60
        val all = listOf(row(10_000_000 - thirtyOneDays), row(10_000_000 - 3600), subject)
        val peers = baselinePeers(all, subject)
        assertEquals(1, peers.size)
        assertEquals(10_000_000L - 3600, peers.first().startTs)
    }

    // MARK: - typicalZoneShare

    /** Five sessions whose zone-1 share climbs 10% → 50%, as raw per-zone percentages. */
    private fun risingZoneOneHistory(): List<List<Double>> =
        listOf(
            listOf(10.0, 90.0, 0.0, 0.0, 0.0),
            listOf(20.0, 80.0, 0.0, 0.0, 0.0),
            listOf(30.0, 70.0, 0.0, 0.0, 0.0),
            listOf(40.0, 60.0, 0.0, 0.0, 0.0),
            listOf(50.0, 50.0, 0.0, 0.0, 0.0),
        )

    @Test fun typicalShare_nullBelowMinimumSessions() {
        val two = risingZoneOneHistory().take(2)
        assertNull("two sessions cannot establish a typical range", typicalZoneShare(two, 0))
    }

    @Test fun typicalShare_bracketsTheMiddleOfTheSpread() {
        val band = typicalZoneShare(risingZoneOneHistory(), 0)
        assertNotNull(band)
        // Shares are 0.1 .. 0.5; the interquartile band should sit inside that, not span it entirely.
        assertTrue("lower edge above the minimum", band!!.start > 0.1f - 1e-6f)
        assertTrue("upper edge below the maximum", band.endInclusive < 0.5f + 1e-6f)
        assertTrue("band is non-empty", band.endInclusive > band.start)
    }

    @Test fun typicalShare_alwaysInsideTheTrack() {
        val band = typicalZoneShare(risingZoneOneHistory(), 0)!!
        assertTrue("start within 0..1", band.start in 0f..1f)
        assertTrue("end within 0..1", band.endInclusive in 0f..1f)
    }

    @Test fun typicalShare_identicalSessionsHaveNoBand() {
        // Every session identical → p25 == p75. A zero-width band would draw two dashed markers on top of
        // each other, which reads as a defect rather than as "you are always here", so it is null instead.
        val flat = List(6) { listOf(25.0, 75.0, 0.0, 0.0, 0.0) }
        assertNull(typicalZoneShare(flat, 0))
    }

    @Test fun typicalShare_ignoresSessionsWithNoRecordedTime() {
        val withEmpties = risingZoneOneHistory() + listOf(listOf(0.0, 0.0, 0.0, 0.0, 0.0))
        // The all-zero session contributes no share; the band is unchanged by its presence.
        assertEquals(typicalZoneShare(risingZoneOneHistory(), 0), typicalZoneShare(withEmpties, 0))
    }

    @Test fun typicalShare_outOfRangeZoneIsNull() {
        assertNull(typicalZoneShare(risingZoneOneHistory(), zoneIndex = 9))
    }

    // MARK: - hmsDuration

    @Test fun hms_formatsHoursMinutesSeconds() {
        assertEquals("1:25:46", hmsDuration(5146.0))
        assertEquals("0:06:11", hmsDuration(371.0))
        assertEquals("0:00:00", hmsDuration(0.0))
    }

    @Test fun hms_rejectsMissingAndNonFinite() {
        assertNull(hmsDuration(null))
        assertNull(hmsDuration(Double.NaN))
        assertNull(hmsDuration(-1.0))
    }
}
