package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.noop.R
import com.noop.data.DailyMetric
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

// MARK: - RecoveryCalendar — a month at a glance, coloured by Charge
//
// The day picker as a month grid whose numerals are tinted by that day's Charge, so choosing a day and
// seeing how the month went are the same gesture. It replaces the stock Material DatePickerDialog, which
// could tell you a date existed but nothing about it.
//
// The colour is NOOP's own continuous recovery ramp (Palette.recoveryColor), not a three-band traffic
// light. Every other Charge readout in the app — the hero vessel, the key tiles, the trend charts —
// samples that same gradient, so banding only here would make the calendar disagree with the ring the
// user just looked at. The legend is therefore a gradient strip rather than three dots: it describes what
// the ramp means without implying cut-offs the data does not have.
//
// A day with no Charge is NOT coloured. Tinting an unscored day would invent a reading, and a grey numeral
// among coloured ones is itself the useful signal ("the strap missed that night").

/** One month grid cell: the date, its Charge if any, and whether it can be chosen.
 *  `internal` rather than private so [calendarGrid] — whose leading-blank and week-start maths is the
 *  part worth pinning with a test — can be exercised from a JVM unit test. */
internal data class CalendarDay(
    val date: LocalDate,
    val charge: Double?,
    val selectable: Boolean,
)

/**
 * Build the padded grid for [month]: leading blanks so the first of the month lands under its weekday,
 * then every day, keyed to [chargeByDay].
 *
 * The week starts on the LOCALE's first day, not a hard-coded Monday — the same month reads Sunday-first
 * in the US and Monday-first in the UK, and a fixed start would silently mislabel every column for half
 * the world. Days after [latest] are present but not selectable: showing the rest of the month keeps the
 * grid a stable shape, while a future day has nothing to select.
 */
internal fun calendarGrid(
    month: YearMonth,
    chargeByDay: Map<LocalDate, Double?>,
    latest: LocalDate,
    firstDayOfWeek: DayOfWeek,
): List<CalendarDay?> {
    val first = month.atDay(1)
    // How many blanks before the 1st: the distance from the locale's week start to the 1st's weekday.
    val lead = ((first.dayOfWeek.value - firstDayOfWeek.value) + 7) % 7
    val cells = ArrayList<CalendarDay?>(lead + month.lengthOfMonth())
    repeat(lead) { cells.add(null) }
    for (d in 1..month.lengthOfMonth()) {
        val date = month.atDay(d)
        cells.add(
            CalendarDay(
                date = date,
                charge = chargeByDay[date],
                selectable = !date.isAfter(latest),
            ),
        )
    }
    return cells
}

/**
 * The weekday header labels, starting at the locale's first day of week.
 *
 * SHORT ("Mon"), not NARROW ("M"): the narrow form collapses Tuesday and Thursday to the same letter in
 * English, and Saturday and Sunday to another, so three of the seven columns cannot be told apart. Seven
 * three-letter labels still fit the grid.
 */
internal fun weekdayLabels(firstDayOfWeek: DayOfWeek, locale: Locale): List<String> =
    (0..6).map { offset ->
        firstDayOfWeek.plus(offset.toLong())
            .getDisplayName(TextStyle.SHORT, locale)
            .uppercase(locale)
    }

/**
 * The Charge-coloured month picker.
 *
 * [days] is the merged daily history; only `day` and `recovery` are read. [latest] bounds selection and
 * forward month navigation — it is the caller's logical "today", which rolls at 04:00, so between midnight
 * and 4am the calendar agrees with the dashboard about which day is current instead of jumping ahead.
 */
@Composable
fun RecoveryCalendarDialog(
    days: List<DailyMetric>,
    selected: LocalDate,
    latest: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = Locale.getDefault()
    val firstDayOfWeek = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val chargeByDay = remember(days) {
        days.mapNotNull { row ->
            runCatching { LocalDate.parse(row.day) }.getOrNull()?.let { it to row.recovery }
        }.toMap()
    }
    var month by remember(selected) { mutableStateOf(YearMonth.from(selected)) }
    val latestMonth = remember(latest) { YearMonth.from(latest) }
    val cells = remember(month, chargeByDay, latest, firstDayOfWeek) {
        calendarGrid(month, chargeByDay, latest, firstDayOfWeek)
    }
    val monthFmt = remember(locale) { DateTimeFormatter.ofPattern("LLLL yyyy", locale) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(Metrics.cardRadius),
            color = Palette.surfaceOverlay,
        ) {
            Column(
                modifier = Modifier.padding(Metrics.space18),
                verticalArrangement = Arrangement.spacedBy(Metrics.space12),
            ) {
                // Month navigator. Forward stops at the month containing `latest`: there is nothing to
                // choose in a future month, and letting the user page into empty grids reads as broken.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { month = month.minusMonths(1) }) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = uiString(R.string.calendar_previous_month),
                            tint = Palette.textPrimary,
                        )
                    }
                    Text(
                        month.atDay(1).format(monthFmt).uppercase(locale),
                        style = NoopType.overline,
                        color = Palette.textPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    val canGoForward = month < latestMonth
                    IconButton(
                        onClick = { if (canGoForward) month = month.plusMonths(1) },
                        enabled = canGoForward,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = uiString(R.string.calendar_next_month),
                            tint = Palette.textPrimary.copy(alpha = if (canGoForward) 1f else 0.28f),
                        )
                    }
                }

                Row(modifier = Modifier.fillMaxWidth()) {
                    weekdayLabels(firstDayOfWeek, locale).forEach { label ->
                        Text(
                            label,
                            style = NoopType.caption,
                            color = Palette.textTertiary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // Fixed seven-column rows. chunked(7) after the leading blanks keeps every date under the
                // right weekday without a nested layout measuring pass.
                cells.chunked(7).forEach { week ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        week.forEach { cell ->
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                if (cell != null) {
                                    CalendarDayCell(
                                        cell = cell,
                                        isSelected = cell.date == selected,
                                        isLatest = cell.date == latest,
                                        onPick = onPick,
                                    )
                                }
                            }
                        }
                        // Pad a short final week so its days keep the same column width as every other row.
                        repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                    }
                }

                ChargeRampLegend()

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text(uiString(R.string.l10n_backup_sync_screen_cancel_77dfd213), color = Palette.accent)
                    }
                }
            }
        }
    }
}

/** One day: the numeral tinted by its Charge, ringed when selected, marked when it is the current day. */
@Composable
private fun CalendarDayCell(
    cell: CalendarDay,
    isSelected: Boolean,
    isLatest: Boolean,
    onPick: (LocalDate) -> Unit,
) {
    val charge = cell.charge
    val tint = when {
        !cell.selectable -> Palette.textTertiary.copy(alpha = 0.35f)
        charge != null -> Palette.recoveryColor(charge)
        else -> Palette.textTertiary
    }
    val label = cell.date.dayOfMonth.toString()
    val describedAs = if (charge != null) {
        uiString(R.string.calendar_day_charge_desc, label, charge.toInt())
    } else {
        uiString(R.string.calendar_day_no_charge_desc, label)
    }
    Box(
        modifier = Modifier
            .padding(Metrics.space2)
            .size(Metrics.calendarCell)
            .clip(CircleShape)
            // The current day gets a faint filled disc; the selected day a brighter one drawn over it
            // (below). Two different marks, because "the day I am looking at" and "the current day" are
            // different facts that are often the same cell - one mark could not say both.
            .then(if (isLatest) Modifier.background(Palette.surfaceInset, CircleShape) else Modifier)
            .clickable(
                enabled = cell.selectable,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onPick(cell.date) },
            )
            .semantics { contentDescription = describedAs },
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(Metrics.calendarCell)
                    .clip(CircleShape)
                    .background(Palette.textPrimary.copy(alpha = 0.14f)),
            )
        }
        Text(
            label,
            style = NoopType.number(15f, weight = if (isSelected || isLatest) FontWeight.Bold else FontWeight.Medium),
            color = tint,
        )
    }
}

/**
 * The ramp legend: a gradient strip from the lowest Charge colour to the highest.
 *
 * A strip rather than WHOOP's three labelled dots, because the tint here is sampled from a continuous
 * gradient. Three dots would advertise cut-offs that do not exist in the data and would not match the
 * colour of a day sitting between them.
 */
@Composable
private fun ChargeRampLegend() {
    val stops = Palette.recoveryStops
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Metrics.space6)
                .clip(RoundedCornerShape(Metrics.cornerXs))
                .background(Brush.horizontalGradient(colorStops = stops.toTypedArray()))
                .clearAndSetSemantics { },
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                uiString(R.string.calendar_legend_low),
                style = NoopType.caption,
                color = Palette.textTertiary,
                modifier = Modifier.weight(1f),
            )
            Text(
                uiString(R.string.calendar_legend_title),
                style = NoopType.caption,
                color = Palette.textSecondary,
            )
            Text(
                uiString(R.string.calendar_legend_high),
                style = NoopType.caption,
                color = Palette.textTertiary,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
