package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text

// MARK: - ComparisonComponents — "this value, judged against its own baseline"
//
// The shared readout grammar for a metric shown NEXT TO the range it usually occupies, rather than as a
// bare number. Three pieces recur across the detail screens and carry the whole idiom:
//
//   • [MetricHeadline]   the big value, a direction glyph, and the baseline underneath in small grey.
//   • [TypicalRangeBar]  a proportional fill over a hatched remainder, with the usual range marked.
//   • [ZoneRowCard]      one HR zone as its own card: label, span, share, duration, and the bar.
//   • [KeyStatTile]      a single statistic against its longer-run average.
//
// Two conventions hold across all of them, and both are deliberate:
//
// 1. DIRECTION IS NOT GOODNESS. A value can fall and that can be good (sleep stress) or bad (sleep
//    efficiency). Every component takes the raw direction plus [higherIsBetter], and derives the tint
//    from the pair — never from the arrow alone. Getting this wrong paints a healthy night in warning
//    colours, which is worse than showing no colour at all.
// 2. CALLERS PASS FORMATTED STRINGS. Nothing here formats a number, a duration or a unit. That keeps
//    locale/unit handling (UnitFormatter, the Effort-scale toggle, translated labels) at the call site
//    where the context lives, and keeps this file free of user-visible literals.
//
// Rendering follows the Charts.kt convention: anything that could become dozens of composables is drawn
// in ONE Canvas with a single collapsed semantics node, so a long list of rows stays cheap to scroll.

/** Where a value sits relative to its baseline. Pair with `higherIsBetter` to get meaning from it. */
enum class MetricDirection { ABOVE, BELOW, LEVEL }

/** The glyph for a direction — the same up/down triangles [TrendChip] uses. LEVEL shows nothing. */
private fun MetricDirection.glyph(): String? = when (this) {
    MetricDirection.ABOVE -> "▲"
    MetricDirection.BELOW -> "▼"
    MetricDirection.LEVEL -> null
}

/**
 * The tint for a direction, resolved through [higherIsBetter] rather than the arrow.
 *
 * A drop in sleep stress and a drop in sleep efficiency point the same way and mean opposite things, so
 * the caller states which way is good and the colour follows from that. LEVEL is deliberately neutral:
 * "unchanged" is information, not a verdict.
 */
@Composable
fun metricDirectionTint(direction: MetricDirection, higherIsBetter: Boolean): Color = when {
    direction == MetricDirection.LEVEL -> Palette.textTertiary
    (direction == MetricDirection.ABOVE) == higherIsBetter -> Palette.statusPositive
    else -> Palette.statusWarning
}

/**
 * Resolve a value and its baseline into a [MetricDirection], treating a near-equal pair as LEVEL.
 *
 * [epsilon] is in the values' own units, so a caller comparing percentages passes something like 0.5 and
 * a caller comparing seconds passes 60 — otherwise a one-second difference would draw an arrow.
 */
fun metricDirection(value: Double?, baseline: Double?, epsilon: Double = 0.0): MetricDirection {
    if (value == null || baseline == null) return MetricDirection.LEVEL
    val d = value - baseline
    return when {
        d > epsilon -> MetricDirection.ABOVE
        d < -epsilon -> MetricDirection.BELOW
        else -> MetricDirection.LEVEL
    }
}

// MARK: - Hatching

/**
 * Fill the current clip with faint diagonal hatching — the shared "this part of the track is not yours"
 * texture. Generalised from the sleep stage timeline, which drew the same 6dp/45° lines inline.
 *
 * Hatching rather than a flat fill matters: an empty remainder drawn solid reads as missing data, while
 * a hatched one reads as "accounted for, just not here". Call inside a `clipRect`/`clipPath`.
 */
internal fun DrawScope.drawDiagonalHatch(
    width: Float,
    height: Float,
    color: Color = Palette.hairline,
    spacing: Dp = 6.dp,
    strokeWidth: Float = 1f,
) {
    val step = spacing.toPx()
    if (step <= 0f || width <= 0f || height <= 0f) return
    var x = -height
    while (x < width) {
        drawLine(
            color = color,
            start = Offset(x, height),
            end = Offset(x + height, 0f),
            strokeWidth = strokeWidth,
        )
        x += step
    }
}

// MARK: - MetricHeadline

/**
 * The big-number readout: value (with an optional smaller unit), a direction glyph, and the baseline
 * beneath it.
 *
 * The baseline line is the point of the component. A bare "93%" tells you nothing about whether that is
 * a good night for THIS person; "93%" over a quiet "94%" does, without needing a sentence. [baseline] is
 * a pre-formatted string in the same units as [value].
 */
@Composable
fun MetricHeadline(
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    baseline: String? = null,
    direction: MetricDirection = MetricDirection.LEVEL,
    higherIsBetter: Boolean = true,
    valueStyle: TextStyle = NoopType.number(34f, weight = FontWeight.Bold),
    contentDescription: String? = null,
) {
    val tint = metricDirectionTint(direction, higherIsBetter)
    val semanticsModifier =
        if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription }
        else Modifier
    Column(
        modifier = modifier.then(semanticsModifier),
        verticalArrangement = Arrangement.spacedBy(Metrics.space2),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Metrics.space4)) {
            Text(value, style = valueStyle, color = Palette.textPrimary, maxLines = 1)
            if (unit != null) {
                Text(
                    unit,
                    style = NoopType.number(15f, weight = FontWeight.Medium),
                    color = Palette.textSecondary,
                    // Lift the unit off the value's descender line so the two read as one lockup.
                    modifier = Modifier.padding(bottom = Metrics.space4),
                    maxLines = 1,
                )
            }
            direction.glyph()?.let {
                Text(
                    it,
                    style = NoopType.number(11f, weight = FontWeight.Bold),
                    color = tint,
                    modifier = Modifier.padding(bottom = Metrics.space8),
                )
            }
        }
        if (baseline != null) {
            Text(baseline, style = NoopType.captionNumber, color = Palette.textTertiary, maxLines = 1)
        }
    }
}

// MARK: - TypicalRangeBar

/**
 * A proportional bar over a hatched remainder, optionally marking the range the value usually falls in.
 *
 * [fraction] and [typicalRange] are both 0..1 of the same track, so a caller normalises against whatever
 * total makes sense (session duration for a zone, the night for a stage). When [typicalRange] is null the
 * bar is just fill-over-hatch — the honest rendering before enough history exists to know what "typical"
 * is for this person, and the reason the range is nullable rather than defaulted to something invented.
 *
 * Drawn in one Canvas with a single semantics node; [contentDescription] should carry the numbers, since
 * none of the geometry is reachable by a screen reader.
 */
@Composable
fun TypicalRangeBar(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    typicalRange: ClosedFloatingPointRange<Float>? = null,
    height: Dp = Metrics.rangeBarHeight,
    contentDescription: String? = null,
) {
    val axModifier =
        if (contentDescription != null) Modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
        else Modifier.clearAndSetSemantics { }
    Canvas(modifier = modifier.fillMaxWidth().height(height).then(axModifier)) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val radius = CornerRadius(Metrics.cornerXs.toPx() / 2f, Metrics.cornerXs.toPx() / 2f)

        // Recessed track + hatch, so the unfilled part reads as "elsewhere", not "no data".
        drawRoundRect(color = Palette.surfaceInset, size = Size(w, h), cornerRadius = radius)
        clipRect(0f, 0f, w, h) { drawDiagonalHatch(width = w, height = h) }

        // The value itself. A non-zero fraction keeps a hairline of colour so a 1% share is still visible
        // as a mark rather than vanishing to nothing.
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) {
            val fillW = maxOf(w * f, Metrics.rangeBarMinFill.toPx()).coerceAtMost(w)
            drawRoundRect(color = color, size = Size(fillW, h), cornerRadius = radius)
        }

        // The usual range: a soft band with a dashed edge at each end.
        if (typicalRange != null) {
            val lo = typicalRange.start.coerceIn(0f, 1f)
            val hi = typicalRange.endInclusive.coerceIn(0f, 1f)
            if (hi > lo) {
                drawRect(
                    color = Palette.textPrimary.copy(alpha = 0.10f),
                    topLeft = Offset(w * lo, 0f),
                    size = Size(w * (hi - lo), h),
                )
            }
            val dash = PathEffect.dashPathEffect(floatArrayOf(3f, 3f), 0f)
            listOf(lo, hi).forEach { at ->
                // Inset by half a stroke at the extremes so a marker at 0 or 1 is not clipped in half.
                val x = (w * at).coerceIn(0.5f, w - 0.5f)
                drawLine(
                    color = Palette.textSecondary,
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 1f,
                    pathEffect = dash,
                    cap = StrokeCap.Butt,
                )
            }
        }
    }
}

// MARK: - ZoneRowCard

/**
 * One heart-rate zone as its own card: label and BPM span on the left, share and duration on the right,
 * and a [TypicalRangeBar] beneath.
 *
 * A card per zone rather than one stacked segment bar: each zone then carries its own numbers and its own
 * comparison against how long this person usually spends there, which a single stacked bar cannot show.
 *
 * [dimmed] is for a zone with no time in it — kept in the list (its absence is itself informative, and
 * dropping it would make the zones jump around between sessions) but visibly stood down.
 */
@Composable
fun ZoneRowCard(
    label: String,
    span: String,
    share: String,
    duration: String,
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    typicalRange: ClosedFloatingPointRange<Float>? = null,
    dimmed: Boolean = false,
    contentDescription: String? = null,
) {
    val rowSemantics =
        if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription }
        else Modifier
    NoopCard(
        modifier = modifier.then(rowSemantics).alpha(if (dimmed) Palette.disabledOpacity else 1f),
        padding = Metrics.space14,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = NoopType.overline, color = Palette.textPrimary)
                Spacer(Modifier.width(Metrics.space8))
                Text(span, style = NoopType.captionNumber, color = Palette.textSecondary, maxLines = 1)
                Spacer(Modifier.width(Metrics.space8))
                Text(share, style = NoopType.captionNumber, color = color, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Text(duration, style = NoopType.number(17f, weight = FontWeight.Bold), color = Palette.textPrimary, maxLines = 1)
            }
            TypicalRangeBar(
                fraction = fraction,
                color = color,
                typicalRange = typicalRange,
                // The row already carries a description; keep the bar out of the a11y tree.
                contentDescription = null,
            )
        }
    }
}

// MARK: - KeyStatTile

/**
 * One statistic against its longer-run average: glyph + label above, value + unit below, and the delta as
 * a [TrendChip].
 *
 * The comparison window ("vs. 30 day average") belongs to the SECTION, not the tile — a grid of tiles all
 * repeating the same window is noise — so it is not a parameter here; the caller puts it in the header.
 */
@Composable
fun KeyStatTile(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    delta: String? = null,
    direction: MetricDirection = MetricDirection.LEVEL,
    higherIsBetter: Boolean = true,
    contentDescription: String? = null,
) {
    val axModifier =
        if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription }
        else Modifier
    NoopCard(modifier = modifier.then(axModifier), padding = Metrics.cardPadding) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                Icon(icon, contentDescription = null, tint = Palette.textSecondary, modifier = Modifier.size(Metrics.iconSmall))
                Text(label, style = NoopType.overline, color = Palette.textSecondary, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Metrics.space4)) {
                AutoSizeValue(
                    text = value,
                    style = NoopType.number(30f, weight = FontWeight.Bold),
                    color = Palette.textPrimary,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (unit != null) {
                    Text(
                        unit,
                        style = NoopType.number(13f, weight = FontWeight.Medium),
                        color = Palette.textSecondary,
                        modifier = Modifier.padding(bottom = Metrics.space4),
                        maxLines = 1,
                    )
                }
            }
            if (delta != null) {
                val glyph = direction.glyph()
                TrendChip(
                    text = if (glyph != null) listOf(glyph, delta).joinToString(" ") else delta,
                    color = metricDirectionTint(direction, higherIsBetter),
                )
            }
        }
    }
}

// MARK: - VitalStatusPill

/** A judged metric's standing: within its expected band, worth a look, or not yet known. */
enum class MetricStatus { GOOD, ATTENTION, UNKNOWN }

/**
 * The small status pill under a vital's value — a tick and the band it was judged against
 * ("within 17.1 – 17.5"), on a tint that carries the verdict.
 *
 * The pill states the RANGE, not just a word. "Normal" asks the reader to trust a judgement; "within
 * 47 – 50" shows them the yardstick and lets them see how close to the edge they are. The caller
 * supplies that text because only it knows whether the band came from a personal baseline or a
 * population fallback.
 *
 * [MetricStatus.UNKNOWN] renders flat grey with no glyph: a tick on "no data" would read as a pass.
 */
@Composable
fun VitalStatusPill(
    text: String,
    status: MetricStatus,
    modifier: Modifier = Modifier,
) {
    val tint = when (status) {
        MetricStatus.GOOD -> Palette.statusPositive
        MetricStatus.ATTENTION -> Palette.statusWarning
        MetricStatus.UNKNOWN -> Palette.textTertiary
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Metrics.cornerXs))
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = Metrics.space8, vertical = Metrics.space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space4),
    ) {
        if (status != MetricStatus.UNKNOWN) {
            Icon(
                if (status == MetricStatus.GOOD) Icons.Filled.Check else Icons.Filled.PriorityHigh,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(Metrics.iconTiny),
            )
        }
        Text(text, style = NoopType.captionNumber, color = tint, maxLines = 2)
    }
}

// MARK: - MetricComparisonRow

/**
 * One contributor row: glyph, name, and the value stacked over the baseline it is being judged against,
 * with a direction arrow between them.
 *
 * The stacked value/baseline pair is the compact form of [MetricHeadline] — same idea, sized for a list
 * where four or five metrics sit under one score. Rows share the arrow vocabulary with everything else
 * here, so a green triangle means the same thing on this row as it does on a detail page.
 */
@Composable
fun MetricComparisonRow(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    baseline: String? = null,
    direction: MetricDirection = MetricDirection.LEVEL,
    higherIsBetter: Boolean = true,
    contentDescription: String? = null,
) {
    val tint = metricDirectionTint(direction, higherIsBetter)
    val axModifier =
        if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription }
        else Modifier
    Row(
        modifier = modifier.fillMaxWidth().then(axModifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Palette.textTertiary, modifier = Modifier.size(Metrics.iconSmall))
        Spacer(Modifier.width(Metrics.space12))
        Text(
            label,
            style = NoopType.subhead.copy(fontWeight = FontWeight.Medium),
            color = Palette.textPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 2,
        )
        Spacer(Modifier.width(Metrics.space8))
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.space4)) {
                Text(value, style = NoopType.number(18f, weight = FontWeight.SemiBold), color = Palette.textPrimary, maxLines = 1)
                // LEVEL still draws a mark — a filled dot — because an empty slot next to four arrows
                // reads as a rendering failure rather than as "unchanged".
                Text(
                    direction.glyph() ?: "●",
                    style = NoopType.number(9f, weight = FontWeight.Bold),
                    color = tint,
                )
            }
            if (baseline != null) {
                Text(baseline, style = NoopType.captionNumber, color = Palette.textTertiary, maxLines = 1)
            }
        }
    }
}

/**
 * The footer that explains what the arrows on a set of [MetricComparisonRow]s are measured against.
 *
 * One legend per group rather than a window label on every row: the comparison is the same for all of
 * them, and repeating it five times crowds out the numbers.
 */
@Composable
fun ComparisonLegend(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Metrics.cornerXs))
            .background(Palette.surfaceInset)
            .padding(horizontal = Metrics.space10, vertical = Metrics.space6),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
    ) {
        Text("▲", style = NoopType.number(9f, weight = FontWeight.Bold), color = Palette.statusPositive)
        Text("▼", style = NoopType.number(9f, weight = FontWeight.Bold), color = Palette.statusWarning)
        Text(text, style = NoopType.caption, color = Palette.textSecondary, maxLines = 2)
    }
}

// MARK: - SelectedColumnBand

/**
 * The translucent vertical band that marks the selected column in a weekly chart.
 *
 * Split out so the bar and line charts mark selection identically. It sits BEHIND the plot (drawn first,
 * as a sibling under the chart in a Box), which is why it is a standalone box rather than a chart flag:
 * a band drawn over the series would dull the very column it is meant to pick out.
 */
@Composable
fun SelectedColumnBand(
    columnCount: Int,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
) {
    if (columnCount <= 0 || selectedIndex !in 0 until columnCount) return
    Canvas(modifier = modifier.fillMaxWidth().clearAndSetSemantics { }) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val slot = w / columnCount
        val radius = CornerRadius(Metrics.cornerXs.toPx(), Metrics.cornerXs.toPx())
        drawRoundRect(
            color = Palette.textPrimary.copy(alpha = 0.07f),
            topLeft = Offset(slot * selectedIndex, 0f),
            size = Size(slot, h),
            cornerRadius = radius,
        )
    }
}

// MARK: - LabelledBarChart

/**
 * A weekly bar chart that labels every bar with its own value and drops the y-axis entirely.
 *
 * Seven bars with the number written above each is more legible than seven bars plus a scale the reader
 * has to interpolate against — there are few enough points that the exact value fits, and the shape still
 * carries the trend. [values] are already normalised 0..1 against whatever the caller's scale is;
 * [labels] are the formatted values to print, and [captions] the axis names beneath.
 *
 * All three lists must be the same length; the shortest wins so a caller can never index out of bounds.
 */
@Composable
fun LabelledBarChart(
    values: List<Float>,
    labels: List<String>,
    captions: List<String>,
    modifier: Modifier = Modifier,
    color: Color = Palette.restColor,
    selectedIndex: Int? = null,
    barHeight: Dp = Metrics.chartHeight,
    contentDescription: String? = null,
) {
    val n = minOf(values.size, labels.size, captions.size)
    if (n == 0) return
    val axModifier =
        if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription }
        else Modifier
    Box(modifier = modifier.fillMaxWidth().then(axModifier)) {
        if (selectedIndex != null) {
            SelectedColumnBand(
                columnCount = n,
                selectedIndex = selectedIndex,
                modifier = Modifier.fillMaxWidth().height(barHeight),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(barHeight),
            verticalAlignment = Alignment.Bottom,
        ) {
            for (i in 0 until n) {
                val selected = i == selectedIndex
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(
                        labels[i],
                        style = NoopType.captionNumber,
                        color = if (selected) Palette.textPrimary else Palette.textSecondary,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(Metrics.space4))
                    // The bar's own share of the remaining height. `weight` on a zero-height spacer above
                    // it would collapse, so the bar is sized directly from the fraction instead.
                    BarColumn(
                        fraction = values[i].coerceIn(0f, 1f),
                        color = color,
                        available = barHeight - Metrics.barLabelReserve,
                        emphasised = selected,
                    )
                    Spacer(Modifier.height(Metrics.space8))
                    Text(
                        captions[i],
                        style = NoopType.footnote,
                        color = if (selected) Palette.textPrimary else Palette.textTertiary,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

/** One bar in [LabelledBarChart]: a rounded column filling [fraction] of [available]. */
@Composable
private fun BarColumn(fraction: Float, color: Color, available: Dp, emphasised: Boolean) {
    val h = (available * fraction).coerceAtLeast(Metrics.rangeBarMinFill)
    Box(
        modifier = Modifier
            .width(Metrics.barWidth)
            .height(available),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Canvas(modifier = Modifier.width(Metrics.barWidth).height(h)) {
            val radius = CornerRadius(Metrics.barWidth.toPx() / 2f, Metrics.barWidth.toPx() / 2f)
            drawRoundRect(
                color = if (emphasised) color else color.copy(alpha = StrandAlpha.unselectedBar),
                size = size,
                cornerRadius = radius,
            )
        }
    }
}
