package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/**
 * The Charge calendar's grid maths.
 *
 * The leading-blank count is the whole correctness problem here: get it wrong and every date in the month
 * sits under the wrong weekday, which looks plausible enough to ship. It also has to be right for BOTH
 * week starts - the same month is Monday-first in the UK and Sunday-first in the US - so a hard-coded
 * Monday would mislabel every column for half the world without failing anything obvious.
 */
class RecoveryCalendarGridTest {

    private val august2026 = YearMonth.of(2026, 8)   // 1 Aug 2026 is a Saturday
    private val farFuture = LocalDate.of(2099, 1, 1)

    private fun grid(
        month: YearMonth = august2026,
        charge: Map<LocalDate, Double?> = emptyMap(),
        latest: LocalDate = farFuture,
        firstDay: DayOfWeek = DayOfWeek.MONDAY,
    ) = calendarGrid(month, charge, latest, firstDay)

    // MARK: - leading blanks

    @Test fun mondayStart_augustLeadsWithFiveBlanks() {
        // 1 Aug 2026 is a Saturday; Monday-first puts it in column 6, so five blanks precede it.
        val cells = grid(firstDay = DayOfWeek.MONDAY)
        assertEquals(5, cells.takeWhile { it == null }.size)
        assertEquals(LocalDate.of(2026, 8, 1), cells[5]!!.date)
    }

    @Test fun sundayStart_augustLeadsWithSixBlanks() {
        // The same Saturday is column 7 when the week starts on Sunday.
        val cells = grid(firstDay = DayOfWeek.SUNDAY)
        assertEquals(6, cells.takeWhile { it == null }.size)
        assertEquals(LocalDate.of(2026, 8, 1), cells[6]!!.date)
    }

    @Test fun monthStartingOnTheWeekStartHasNoBlanks() {
        // 1 Jun 2026 is a Monday.
        val cells = calendarGrid(YearMonth.of(2026, 6), emptyMap(), farFuture, DayOfWeek.MONDAY)
        assertNull(cells.firstOrNull { it == null })
        assertEquals(LocalDate.of(2026, 6, 1), cells.first()!!.date)
    }

    // MARK: - completeness

    @Test fun everyDayOfTheMonthIsPresentExactlyOnce() {
        val dates = grid().filterNotNull().map { it.date }
        assertEquals(31, dates.size)
        assertEquals(dates.size, dates.toSet().size)
        assertEquals(LocalDate.of(2026, 8, 31), dates.last())
    }

    @Test fun februaryLeapYearHasTwentyNineDays() {
        val cells = calendarGrid(YearMonth.of(2028, 2), emptyMap(), farFuture, DayOfWeek.MONDAY)
        assertEquals(29, cells.filterNotNull().size)
    }

    // MARK: - selectability

    @Test fun daysAfterLatestAreShownButNotSelectable() {
        val latest = LocalDate.of(2026, 8, 18)
        val cells = grid(latest = latest).filterNotNull()
        // The rest of the month stays in the grid so its shape does not change mid-month...
        assertEquals(31, cells.size)
        // ...but nothing after the current day can be chosen.
        assertTrue(cells.filter { !it.date.isAfter(latest) }.all { it.selectable })
        assertTrue(cells.filter { it.date.isAfter(latest) }.none { it.selectable })
    }

    @Test fun latestItselfIsSelectable() {
        val latest = LocalDate.of(2026, 8, 18)
        val cell = grid(latest = latest).filterNotNull().first { it.date == latest }
        assertTrue("the current day must be choosable", cell.selectable)
    }

    // MARK: - charge mapping

    @Test fun chargeIsAttachedToTheMatchingDayOnly() {
        val day = LocalDate.of(2026, 8, 12)
        val cells = grid(charge = mapOf(day to 54.0)).filterNotNull()
        assertEquals(54.0, cells.first { it.date == day }.charge!!, 1e-9)
        assertNull("an unscored day carries no charge", cells.first { it.date == day.plusDays(1) }.charge)
    }

    @Test fun aDayPresentWithNullChargeStaysUncoloured() {
        // A row can exist for a day without a Charge (the strap missed the night). That must read as
        // "no value", not as zero - a zero would paint the day the lowest colour on the ramp.
        val day = LocalDate.of(2026, 8, 12)
        val cells = grid(charge = mapOf(day to null)).filterNotNull()
        assertNull(cells.first { it.date == day }.charge)
    }

    // MARK: - weekday labels

    @Test fun weekdayLabelsAreARotationOfTheSameSevenDays() {
        val monday = weekdayLabels(DayOfWeek.MONDAY, Locale.UK)
        val sunday = weekdayLabels(DayOfWeek.SUNDAY, Locale.US)
        assertEquals(7, monday.size)
        assertEquals(7, sunday.size)
        // Sunday-first is Monday-first rotated RIGHT by one: [Sun, Mon..Sat] vs [Mon..Sat, Sun].
        assertEquals(monday, sunday.drop(1) + sunday.first())
    }

    @Test fun weekdayLabelsAreDistinguishable() {
        // The narrow form collapses Tue/Thu to "T" and Sat/Sun to "S" in English, leaving three columns
        // unreadable. Seven labels must stay seven distinct labels.
        val labels = weekdayLabels(DayOfWeek.MONDAY, Locale.UK)
        assertEquals("every weekday must be distinguishable", 7, labels.toSet().size)
        assertFalse(labels.any { it.isBlank() })
    }
}
