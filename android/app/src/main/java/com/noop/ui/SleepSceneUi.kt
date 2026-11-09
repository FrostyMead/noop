package com.noop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.noop.R
import kotlin.math.roundToInt

// MARK: - Sleep tab scene
//
// The Sleep tab opens on its own night: an indigo band that settles into the canvas, the night's score as
// one large ring sitting directly on it, and two translucent tiles for time in bed and time asleep. All
// colour comes from Palette (the Rest family and the restScene tokens); every figure is passed in by the
// caller, so this file holds presentation only.

/** Fixed star positions as (x, y, radius) fractions of the scene: deterministic, so the field never jumps
 *  between recompositions, and confined to the upper band where the scene is darkest. */
private val SCENE_STARS = listOf(
    Triple(0.08f, 0.06f, 1.0f), Triple(0.19f, 0.15f, 0.7f), Triple(0.31f, 0.04f, 0.8f),
    Triple(0.44f, 0.11f, 0.6f), Triple(0.57f, 0.03f, 1.1f), Triple(0.66f, 0.17f, 0.7f),
    Triple(0.78f, 0.08f, 0.9f), Triple(0.91f, 0.14f, 0.6f), Triple(0.13f, 0.27f, 0.6f),
    Triple(0.86f, 0.26f, 0.8f), Triple(0.37f, 0.22f, 0.5f), Triple(0.71f, 0.29f, 0.5f),
)

/** The night scene for the Sleep tab's `topBackground` slot. */
@Composable
internal fun SleepNightScene() {
    val top = Palette.restSceneTop
    val mid = Palette.restSceneMid
    val canvas = Palette.surfaceBase
    val star = Palette.sceneStar
    Box(
        Modifier
            .fillMaxWidth()
            .height(SleepSceneStyle.sceneHeight)
            .drawBehind {
                drawRect(Brush.verticalGradient(0f to top, 0.45f to mid, 1f to canvas))
                if (star.alpha > 0f) {
                    val unit = size.minDimension / 200f
                    SCENE_STARS.forEach { (x, y, r) ->
                        drawCircle(star.copy(alpha = star.alpha * (0.5f + r / 2f)), radius = r * unit,
                            center = Offset(x * size.width, y * size.height))
                    }
                }
            },
    )
}

/** The night's score as one large ring, the number and its word at the centre. */
@Composable
internal fun SleepScoreRing(score: Double, word: String) {
    val reduceMotion = rememberReduceMotion()
    val fraction = (score / 100.0).coerceIn(0.0, 1.0).toFloat()
    val sweep by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(if (reduceMotion) 0 else Motion.durationSlow, easing = Motion.drawIn),
    )
    val track = Palette.textPrimary.copy(alpha = SleepSceneStyle.ringTrackAlpha)
    val deep = Palette.restDeep
    val bright = Palette.restBright
    Box(
        Modifier
            .size(SleepSceneStyle.ringDiameter)
            .semantics(mergeDescendants = true) {},
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = SleepSceneStyle.ringStroke.toPx()
            val inset = stroke / 2f
            val topLeft = Offset(inset, inset)
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(track, -90f, 360f, false, topLeft = topLeft, size = arc, style = Stroke(stroke))
            if (sweep > 0f) {
                rotate(-90f) {
                    drawArc(
                        brush = Brush.sweepGradient(0f to deep, sweep.coerceAtLeast(0.01f) to bright, center = center),
                        startAngle = 0f, sweepAngle = 360f * sweep, useCenter = false,
                        topLeft = topLeft, size = arc, style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                    // The start cap's overhang sits where the sweep gradient wraps to its brightest stop.
                    drawCircle(deep, radius = inset, center = Offset(size.width - inset, center.y))
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    score.roundToInt().toString(),
                    style = NoopType.number(52f, FontWeight.Medium).copy(letterSpacing = (-1).sp),
                    color = Palette.textPrimary,
                    modifier = Modifier.alignByBaseline(),
                )
                Text("%", style = NoopType.number(20f, FontWeight.Medium), color = Palette.textSecondary,
                    modifier = Modifier.alignByBaseline())
            }
            Text(word, style = NoopType.subhead, color = Palette.textSecondary)
        }
    }
}

/** A translucent value tile on the scene: a small muted label with its glyph, then the value. */
@Composable
internal fun SleepSceneTile(icon: ImageVector, label: String, value: String?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Metrics.cornerMd)
    Column(
        modifier
            .clip(shape)
            .background(Palette.surfaceRaised.copy(alpha = SleepSceneStyle.tileFillAlpha * CardAppearance.opacity), shape)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = Metrics.space14, vertical = Metrics.space12),
        verticalArrangement = Arrangement.spacedBy(Metrics.space6),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.space6)) {
            Icon(icon, contentDescription = null, tint = Palette.textTertiary, modifier = Modifier.size(TodayStyle.eyebrowIcon))
            Text(label, style = NoopType.eyebrow, color = Palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            value ?: "—",
            style = NoopType.tileValue,
            color = if (value == null) Palette.textTertiary else Palette.textPrimary,
            maxLines = 1,
        )
    }
}

/**
 * The Sleep tab's hero: the night picker ([header]), the score ring (or, for a night with no 0-100 score, hours
 * asleep as the headline), where the score came from, and the in-bed / asleep tiles.
 */
@Composable
internal fun SleepSceneHero(
    header: @Composable () -> Unit,
    score: Double?,
    scoreWord: String?,
    asleepMin: Double?,
    inBedMin: Double?,
    source: String,
) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Metrics.space16),
    ) {
        header()
        if (score != null) {
            SleepScoreRing(score, scoreWord ?: "")
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Metrics.space4),
                modifier = Modifier.padding(vertical = Metrics.space24)) {
                CountUpText(
                    value = asleepMin ?: 0.0,
                    format = { durationText(it) },
                    style = NoopType.number(46f, FontWeight.Medium),
                    color = Palette.textPrimary,
                )
                Text(uiString(R.string.l10n_sleep_screen_asleep_last_night_b969b068), style = NoopType.subhead, color = Palette.textSecondary)
            }
        }
        if (score != null) {
            Text(uiString(R.string.sleep_scene_performance) + " · " + source,
                style = NoopType.caption, color = Palette.textTertiary)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
            SleepSceneTile(Icons.Filled.Hotel, uiString(R.string.sleep_scene_in_bed), inBedMin?.let { durationText(it) },
                Modifier.weight(1f))
            SleepSceneTile(Icons.Filled.Bedtime, uiString(R.string.sleep_scene_asleep), asleepMin?.let { durationText(it) },
                Modifier.weight(1f))
        }
    }
}
