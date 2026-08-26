package com.noop.ui

import com.noop.data.WorkoutRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Day attribution for Today's activity list.
 *
 * The 04:00 rollover is the whole point: a session started at 1am belongs to the night before, which is
 * the day whose Effort it was banked into. Attributing it to the calendar date instead would file it under
 * a day whose scores never saw it — visible to the user as a workout appearing under the wrong ring.
 */
class WorkoutsOnDayTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun rowAt(local: String, sport: String = "run", device: String = "strap"): WorkoutRow {
        val start = LocalDateTime.parse(local).atZone(zone).toEpochSecond()
        return WorkoutRow(
            deviceId = device,
            startTs = start,
            endTs = start + 1800,
            sport = sport,
            source = "whoop",
            durationS = 1800.0,
        )
    }

    private val aug18 = LocalDate.of(2026, 8, 18)

    @Test fun anEveningSessionBelongsToThatCalendarDay() {
        val rows = listOf(rowAt("2026-08-18T21:36"))
        assertEquals(1, workoutsOnDay(rows, aug18, zone).size)
    }

    @Test fun aLateNightSessionBelongsToTheDayBefore() {
        // 01:30 on the 19th is still the 18th's night under the 04:00 roll.
        val rows = listOf(rowAt("2026-08-19T01:30"))
        assertEquals(1, workoutsOnDay(rows, aug18, zone).size)
        assertTrue(workoutsOnDay(rows, LocalDate.of(2026, 8, 19), zone).isEmpty())
    }

    @Test fun theRolloverBoundaryItself() {
        // 03:59 still belongs to the previous day; 04:00 starts the new one.
        val justBefore = listOf(rowAt("2026-08-19T03:59"))
        val atRoll = listOf(rowAt("2026-08-19T04:00"))
        assertEquals(1, workoutsOnDay(justBefore, aug18, zone).size)
        assertTrue(workoutsOnDay(atRoll, aug18, zone).isEmpty())
        assertEquals(1, workoutsOnDay(atRoll, LocalDate.of(2026, 8, 19), zone).size)
    }

    @Test fun otherDaysAreExcluded() {
        val rows = listOf(
            rowAt("2026-08-17T18:00"),
            rowAt("2026-08-18T18:00"),
            rowAt("2026-08-19T18:00"),
        )
        val onDay = workoutsOnDay(rows, aug18, zone)
        assertEquals(1, onDay.size)
        assertEquals(rowAt("2026-08-18T18:00").startTs, onDay.first().startTs)
    }

    @Test fun resultIsNewestFirst() {
        val rows = listOf(
            rowAt("2026-08-18T07:00"),
            rowAt("2026-08-18T21:00"),
            rowAt("2026-08-18T14:00"),
        )
        val starts = workoutsOnDay(rows, aug18, zone).map { it.startTs }
        assertEquals(starts.sortedDescending(), starts)
    }

    @Test fun aRestDayIsEmpty() {
        val rows = listOf(rowAt("2026-08-17T18:00"))
        assertTrue(workoutsOnDay(rows, aug18, zone).isEmpty())
    }

    @Test fun crossSourceDuplicatesCollapse() {
        // The same session imported from two sources must list once, not twice - the dedup that the old
        // feed applied has to survive the switch to day scoping.
        val rows = listOf(
            rowAt("2026-08-18T18:00", device = "strap"),
            rowAt("2026-08-18T18:00", device = "apple-health"),
        )
        assertEquals(1, workoutsOnDay(rows, aug18, zone).size)
    }
}
