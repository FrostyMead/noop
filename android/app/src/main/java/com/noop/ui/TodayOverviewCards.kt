package com.noop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.analytics.VitalBands
import com.noop.data.DailyMetric
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// MARK: - Daily overview building blocks
//
// The Today screen's own cards: one card of three score rings, a short insight, the expandable health
// monitor and a sleep summary. Every colour, radius and size comes from Palette / Metrics / TodayStyle;
// the surfaces share NoopCard's frosted-card painter so the card-transparency setting and the light
// theme's soft shadow apply here exactly as on every other screen.

/** One opaque overview surface. [tint] adds the faint domain wash NoopCard uses. */
@Composable
internal fun TodayOverviewCard(
    modifier: Modifier = Modifier,
    tint: Color? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(TodayStyle.cardRadius)
    Box(
        modifier
            .fillMaxWidth()
            .frostedCardSurface(tint = tint, cornerRadius = TodayStyle.cardRadius)
            .clip(shape),
    ) { content() }
}

/** A domain icon on a soft disc of its own colour — the leading mark on every overview card. */
@Composable
internal fun TodayIconDisc(icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(TodayStyle.iconDisc)
            .clip(CircleShape)
            .background(tint.copy(alpha = TodayStyle.iconDiscAlpha)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Metrics.iconSmall))
    }
}

/** The small muted label that opens a card, with its glyph and an optional trailing note. */
@Composable
internal fun TodayEyebrow(icon: ImageVector, text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
    ) {
        Icon(icon, contentDescription = null, tint = Palette.textTertiary, modifier = Modifier.size(TodayStyle.eyebrowIcon))
        Text(text, style = NoopType.eyebrow, color = Palette.textSecondary, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, style = NoopType.caption, color = Palette.textTertiary, maxLines = 1) }
    }
}

/** One score in the hero. [fraction] null means there is no score to draw — the ring stays a bare track. */
internal data class TodayScore(
    val label: String,
    val value: String,
    /** A small suffix set beside the number ("%"); null for a unitless index such as Effort. */
    val unit: String?,
    val fraction: Double?,
    /** A state note (carried night, calibrating, nothing yet). Null when the number speaks for itself. */
    val caption: String?,
    val tint: Color,
    /** The darker end of the score's family; the arc grows from it into [tint]. */
    val deep: Color,
    val onOpen: () -> Unit,
)

/** The three daily scores as rings, side by side on one card; [footnote] names where they came from. */
@Composable
internal fun TodayScoreRings(scores: List<TodayScore>, modifier: Modifier = Modifier, footnote: String? = null) {
    TodayOverviewCard(modifier) {
        Column(
            Modifier.padding(top = Metrics.space12, bottom = if (footnote == null) Metrics.space12 else Metrics.space14),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Metrics.space4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                scores.forEachIndexed { i, score ->
                    if (i > 0) {
                        Box(Modifier.width(1.dp).height(TodayStyle.ringDivider).background(Palette.hairline))
                    }
                    TodayScoreRing(score, Modifier.weight(1f))
                }
            }
            if (footnote != null) {
                Text(
                    footnote, style = NoopType.footnote, color = Palette.textTertiary, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = Metrics.space6),
                )
            }
        }
    }
}

@Composable
private fun TodayScoreRing(score: TodayScore, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val target = score.fraction?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)?.toFloat() ?: 0f
    val sweep by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(if (reduceMotion) 0 else Motion.durationSlow, easing = Motion.drawIn),
    )
    Column(
        modifier
            .clip(RoundedCornerShape(Metrics.cornerMd))
            .clickable(role = Role.Button, onClick = score.onOpen)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = Metrics.space4, vertical = Metrics.space8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Metrics.space10),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val diameter = maxWidth.coerceAtMost(TodayStyle.scoreDiameter)
            Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = TodayStyle.scoreStroke.toPx()
                    val inset = stroke / 2f
                    val topLeft = Offset(inset, inset)
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(
                        color = Palette.surfaceInset,
                        startAngle = -90f, sweepAngle = 360f, useCenter = false,
                        topLeft = topLeft, size = arcSize, style = Stroke(stroke),
                    )
                    if (score.fraction != null && sweep > 0f) {
                        // The arc deepens toward its start and brightens toward its tip. The sweep gradient
                        // is laid out from 3 o'clock, so the canvas is turned a quarter to start it at 12.
                        rotate(-90f) {
                            drawArc(
                                brush = Brush.sweepGradient(
                                    0f to score.deep, sweep.coerceAtLeast(0.01f) to score.tint, center = center,
                                ),
                                startAngle = 0f, sweepAngle = 360f * sweep, useCenter = false,
                                topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
                            )
                            // Repaint the start cap: its rounded overhang sits just behind 0 degrees, where
                            // the sweep gradient wraps to its brightest stop.
                            drawCircle(score.deep, radius = inset, center = Offset(size.width - inset, center.y))
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.padding(horizontal = TodayStyle.scoreStroke + Metrics.space4),
                ) {
                    AutoSizeValue(
                        text = score.value,
                        style = NoopType.ringValue,
                        color = if (score.fraction == null) Palette.textTertiary else Palette.textPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.alignByBaseline().weight(1f, fill = false),
                    )
                    if (score.unit != null && score.fraction != null) {
                        Text(
                            score.unit, style = NoopType.ringUnit, color = Palette.textSecondary,
                            modifier = Modifier.alignByBaseline(),
                        )
                    }
                }
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Metrics.space2),
        ) {
            Text(
                score.label,
                style = NoopType.caption.copy(fontWeight = FontWeight.Medium),
                color = Palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            score.caption?.let {
                Text(
                    it,
                    style = NoopType.footnote, color = Palette.textTertiary,
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The day's read as one plain sentence; tapping opens the full breakdown. [label] names it for TalkBack. */
@Composable
internal fun TodayInsightCard(label: String, detail: String, onOpen: () -> Unit) {
    TodayOverviewCard {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = label, onClick = onOpen)
                .padding(Metrics.space16),
            verticalArrangement = Arrangement.spacedBy(Metrics.space6),
        ) {
            TodayEyebrow(Icons.Filled.AutoAwesome, uiString(R.string.overview_insight_label))
            Text(detail, style = NoopType.body, color = Palette.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The selected day wins per field; respiratory uses its own staleness-bounded carry. */
internal fun todayMonitorSources(day: DailyMetric?, vitalsDay: DailyMetric?, respDay: DailyMetric?): List<Pair<String, DailyMetric?>> =
    listOf(
        "hrv" to (day?.takeIf { it.avgHrv != null } ?: vitalsDay),
        "rhr" to (day?.takeIf { it.restingHr != null } ?: vitalsDay),
        "resp" to (day?.takeIf { it.respRateBpm != null } ?: respDay),
    )

internal enum class TodayMonitorStatus { EMPTY, PARTIAL, OUTSIDE, PERSONAL, TYPICAL }

internal fun todayMonitorStatus(results: List<VitalBands.Result>): TodayMonitorStatus = when {
    results.isEmpty() || results.all { it.band == VitalBands.Band.NO_DATA } -> TodayMonitorStatus.EMPTY
    results.any { it.band == VitalBands.Band.OUT_OF_RANGE } -> TodayMonitorStatus.OUTSIDE
    results.any { it.band == VitalBands.Band.NO_DATA } -> TodayMonitorStatus.PARTIAL
    results.all { it.basis == VitalBands.Basis.PERSONAL } -> TodayMonitorStatus.PERSONAL
    else -> TodayMonitorStatus.TYPICAL
}

@Composable
internal fun TodayHealthMonitor(
    dayKey: String,
    day: DailyMetric?,
    vitalsDay: DailyMetric?,
    respDay: DailyMetric?,
    days: List<DailyMetric>,
    onOpenMetric: (String) -> Unit,
) {
    var expanded by rememberSaveable(dayKey) { mutableStateOf(false) }
    val duration = if (rememberReduceMotion()) 0 else Motion.durationStandard
    val readings = remember(day, vitalsDay, respDay, days) {
        todayMonitorSources(day, vitalsDay, respDay).map { (key, source) ->
            vitalsFor(source, days).first { it.key == key }
        }
    }
    val status = todayMonitorStatus(readings.map { it.banding })
    val statusText = uiString(when (status) {
        TodayMonitorStatus.EMPTY -> R.string.overview_monitor_empty
        TodayMonitorStatus.PARTIAL -> R.string.overview_monitor_partial
        TodayMonitorStatus.OUTSIDE -> R.string.overview_monitor_outside
        TodayMonitorStatus.PERSONAL -> R.string.overview_monitor_personal
        TodayMonitorStatus.TYPICAL -> R.string.overview_monitor_typical
    })
    val expandedText = uiString(if (expanded) R.string.overview_expanded else R.string.overview_collapsed)
    TodayOverviewCard {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = uiString(if (expanded)
                        R.string.today_action_collapse else R.string.today_action_expand_full_read)) { expanded = !expanded }
                    .semantics { stateDescription = expandedText }
                    .padding(Metrics.space16),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.space12),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space6)) {
                    TodayEyebrow(Icons.Filled.MonitorHeart, uiString(R.string.overview_health_monitor))
                    // Colour lives on the per-reading dots; the summary line stays neutral.
                    Text(statusText, style = NoopType.body, color = Palette.textPrimary)
                }
                Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null, tint = Palette.textTertiary, modifier = Modifier.size(Metrics.iconSmall))
            }
            // Collapsed, the three readings still show at a glance; expanding swaps them for the full rows.
            AnimatedVisibility(visible = !expanded,
                enter = expandVertically(animationSpec = tween(duration)),
                exit = shrinkVertically(animationSpec = tween(duration))) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = Metrics.space16, end = Metrics.space16, bottom = Metrics.space16),
                    horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
                ) {
                    readings.forEach { vital ->
                        TodayMonitorChip(vital, Modifier.weight(1f)) { onOpenMetric(vital.key) }
                    }
                }
            }
            AnimatedVisibility(visible = expanded,
                enter = expandVertically(animationSpec = tween(duration)),
                exit = shrinkVertically(animationSpec = tween(duration))) {
                Column(Modifier.padding(horizontal = Metrics.space16)) {
                    readings.forEach { vital ->
                        val outside = vital.banding.band == VitalBands.Band.OUT_OF_RANGE
                        val missing = vital.banding.band == VitalBands.Band.NO_DATA
                        HorizontalDivider(color = Palette.hairline)
                        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) {
                            onOpenMetric(vital.key)
                        }.padding(vertical = Metrics.space14), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                            Box(
                                Modifier
                                    .size(TodayStyle.legendDot)
                                    .clip(CircleShape)
                                    .background(when {
                                        missing -> Palette.hairlineStrong
                                        outside -> Palette.statusWarning
                                        else -> Palette.chargeColor
                                    }),
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
                                Text(vital.label, style = NoopType.subhead.copy(fontWeight = FontWeight.SemiBold),
                                    color = Palette.textPrimary)
                                vital.asOfLabel?.let { Text(it, style = NoopType.caption, color = Palette.textSecondary) }
                            }
                            Column(horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
                                Text(vital.formattedValue ?: uiString(R.string.today_no_data),
                                    style = NoopType.chartValue, color = Palette.textPrimary)
                                Text(uiString(when {
                                    missing -> R.string.today_no_data
                                    vital.banding.basis == VitalBands.Basis.PERSONAL ->
                                        if (!outside) R.string.overview_in_personal else R.string.overview_out_personal
                                    else -> if (!outside) R.string.overview_in_typical else R.string.overview_out_typical
                                }), style = NoopType.caption,
                                    color = if (outside) Palette.statusWarning else Palette.textSecondary)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One reading in the collapsed monitor: its name, its value with a small unit, and a status dot. */
@Composable
private fun TodayMonitorChip(vital: Vital, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val outside = vital.banding.band == VitalBands.Band.OUT_OF_RANGE
    val missing = vital.banding.band == VitalBands.Band.NO_DATA || vital.value == null
    Column(
        modifier
            .clip(RoundedCornerShape(Metrics.cornerMd))
            .background(Palette.surfaceInset.copy(alpha = TodayStyle.insetAlpha))
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = Metrics.space10, vertical = Metrics.space10),
        verticalArrangement = Arrangement.spacedBy(Metrics.space4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.space4)) {
            Box(
                Modifier
                    .size(TodayStyle.statusDot)
                    .clip(CircleShape)
                    .background(when {
                        missing -> Palette.hairlineStrong
                        outside -> Palette.statusWarning
                        else -> Palette.chargeColor
                    }),
            )
            Text(vital.label, style = NoopType.footnote, color = Palette.textSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                vital.value?.let(vital.format) ?: "—",
                style = NoopType.number(18f, FontWeight.SemiBold),
                color = if (missing) Palette.textTertiary else Palette.textPrimary,
                maxLines = 1,
                modifier = Modifier.alignByBaseline(),
            )
            if (!missing && vital.unit.isNotEmpty()) {
                Text(" " + vital.unit, style = NoopType.caption, color = Palette.textSecondary,
                    maxLines = 1, modifier = Modifier.alignByBaseline())
            }
        }
    }
}

/** One entry in the day's activity list: icon disc, name and time, and a value on the right. */
@Composable
internal fun TodayActivityRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    value: String,
    detail: String?,
    onClickLabel: String,
    onOpen: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onOpen)
            .padding(horizontal = Metrics.space16, vertical = Metrics.space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space12),
    ) {
        Box(
            Modifier
                .size(TodayStyle.activityIcon)
                .clip(RoundedCornerShape(Metrics.cornerSm))
                .background(Palette.surfaceInset.copy(alpha = TodayStyle.insetAlpha)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Metrics.iconSmall))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
            Text(title, style = NoopType.subhead.copy(fontWeight = FontWeight.Medium), color = Palette.textPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = NoopType.caption, color = Palette.textSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
            Text(value, style = NoopType.number(17f, FontWeight.SemiBold), color = Palette.textPrimary, maxLines = 1)
            detail?.let { Text(it, style = NoopType.caption, color = Palette.textSecondary, maxLines = 1) }
        }
    }
}

/** "Mon 7 Sep" for a stored `yyyy-MM-dd` day key; the raw key if it does not parse. */
private fun overviewNightLabel(dayKey: String): String =
    runCatching {
        LocalDate.parse(dayKey).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
    }.getOrDefault(dayKey)

@Composable
internal fun TodaySleepOverview(day: DailyMetric?, onOpen: () -> Unit) {
    TodayOverviewCard {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(Metrics.space16),
            verticalArrangement = Arrangement.spacedBy(Metrics.space12),
        ) {
            TodayEyebrow(
                Icons.Filled.Bedtime, uiString(R.string.overview_sleep_summary),
                trailing = day?.day?.let { overviewNightLabel(it) },
            )
            val total = day?.totalSleepMin
            Text(
                total?.let { minutes ->
                    uiString(R.string.overview_sleep_duration, minutes.toInt() / 60, minutes.toInt() % 60)
                } ?: uiString(R.string.today_no_data),
                style = NoopType.tileValueLarge,
                color = if (total == null) Palette.textTertiary else Palette.textPrimary,
            )
            // Daily aggregates are durations, not an epoch timeline: a proportional bar, never a hypnogram.
            val stages = listOf(
                Triple(R.string.overview_sleep_deep, day?.deepMin, Palette.sleepDeep),
                Triple(R.string.overview_sleep_rem, day?.remMin, Palette.sleepREM),
                Triple(R.string.overview_sleep_light, day?.lightMin, Palette.sleepLight),
            )
            val present = stages.filter { (it.second ?: 0.0) > 0 }
            if (present.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(TodayStyle.stageBarHeight)
                        .clip(RoundedCornerShape(Metrics.cornerPill)),
                    horizontalArrangement = Arrangement.spacedBy(Metrics.space2),
                ) {
                    present.forEach { (label, minutes, color) ->
                        val description = uiString(R.string.overview_stage_minutes, uiString(label), minutes!!.toInt())
                        Box(
                            Modifier
                                .weight(minutes.toFloat())
                                .fillMaxHeight()
                                .background(color)
                                .semantics { contentDescription = description },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                    stages.filter { it.second != null }.forEach { (label, minutes, color) ->
                        Column(
                            Modifier.weight(1f).semantics(mergeDescendants = true) {},
                            verticalArrangement = Arrangement.spacedBy(Metrics.space2),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
                            ) {
                                Box(Modifier.size(TodayStyle.legendDot).clip(CircleShape).background(color))
                                Text(uiString(label), style = NoopType.caption, color = Palette.textSecondary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(
                                uiString(R.string.overview_minutes, minutes!!.toInt()),
                                style = NoopType.number(15f, FontWeight.SemiBold), color = Palette.textPrimary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            } else {
                Text(uiString(R.string.overview_sleep_details), style = NoopType.subhead, color = Palette.textSecondary)
            }
        }
    }
}
