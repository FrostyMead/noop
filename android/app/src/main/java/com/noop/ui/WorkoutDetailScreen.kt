package com.noop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.HeartRateRecovery
import com.noop.analytics.HrZoneSet
import com.noop.analytics.RouteMath
import com.noop.analytics.TimeInZone
import com.noop.analytics.WorkoutSport
import com.noop.data.StrengthWorkout
import com.noop.data.WorkoutRow
import com.noop.ingest.RouteExport
import java.util.Locale
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: - WorkoutDetailScreen — one session, full page
//
// The full-screen read of a single workout: its Effort against the sport's own recent average, the heart
// rate trace, the time-in-zone split as one card per zone, and the headline statistics against a 30-day
// baseline. Reachable from the Today dashboard and the Workouts session list.
//
// Why a page and not the modal sheet this grew out of: a sheet cannot own a title, a back affordance or a
// deep link, and it stacks awkwardly when a row is tapped from two different screens. The sheet remains
// for the quick in-list peek; this is the destination.
//
// EVERY comparison here is against the SAME SPORT. A jiu jitsu session measured against an average that
// includes easy walks is not a comparison, it is noise — so the baselines filter by sport first and simply
// omit themselves when too few sessions exist to say anything. Nothing on this page invents a range.

/** How many prior same-sport sessions a baseline needs before it is worth showing. */
private const val MIN_BASELINE_SESSIONS = 3

/** The trailing window the baselines are computed over, in seconds (30 days). */
private const val BASELINE_WINDOW_SECONDS = 30L * 24 * 60 * 60

/**
 * The p25..p75 band of one zone's share across prior sessions, as a 0..1 range for [TypicalRangeBar].
 *
 * An interquartile band rather than a mean ± something: zone shares are bounded at 0 and 1 and are often
 * lopsided (most sessions barely touch Zone 5), so a symmetric spread around a mean routinely produces a
 * band running below zero or above the track. Quartiles cannot.
 *
 * Returns null below [minSessions] samples — the honest answer before there is a personal norm, and the
 * reason the bar's range parameter is nullable.
 */
internal fun typicalZoneShare(
    history: List<List<Double>>,
    zoneIndex: Int,
    minSessions: Int = MIN_BASELINE_SESSIONS,
): ClosedFloatingPointRange<Float>? {
    val shares = history.mapNotNull { session ->
        val total = session.sum()
        if (total <= 0.0 || zoneIndex !in session.indices) null else session[zoneIndex] / total
    }.sorted()
    if (shares.size < minSessions) return null
    // Nearest-rank quartiles: with a handful of sessions, interpolating between neighbours implies a
    // precision the sample size does not support.
    fun quantile(q: Double): Float {
        val idx = ((shares.size - 1) * q).roundToInt().coerceIn(0, shares.size - 1)
        return shares[idx].toFloat().coerceIn(0f, 1f)
    }
    val lo = quantile(0.25)
    val hi = quantile(0.75)
    return if (hi > lo) lo..hi else null
}

/** The mean of a metric across prior same-sport sessions, or null below [MIN_BASELINE_SESSIONS]. */
internal fun sessionBaseline(values: List<Double>): Double? =
    if (values.size < MIN_BASELINE_SESSIONS) null else values.sum() / values.size

/** Prior same-sport sessions inside the baseline window, excluding the session being viewed. */
internal fun baselinePeers(all: List<WorkoutRow>, row: WorkoutRow): List<WorkoutRow> =
    all.filter {
        it.sport.equals(row.sport, ignoreCase = true) &&
            it.startTs < row.startTs &&
            it.startTs >= row.startTs - BASELINE_WINDOW_SECONDS
    }

/** "1:25:46" — the H:MM:SS duration WHOOP-style detail headers use. */
internal fun hmsDuration(seconds: Double?): String? {
    val s = seconds?.takeIf { it.isFinite() && it >= 0 }?.toLong() ?: return null
    return String.format(Locale.getDefault(), "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
}

/**
 * The full-page read of one workout.
 *
 * [startTs] and [deviceId] together identify the session (the storage key), resolved against the loaded
 * workout list rather than re-queried, so opening the page cannot show a different row than the list that
 * launched it. A session that has since been deleted or not yet loaded renders an honest empty state.
 */
@Composable
fun WorkoutDetailScreen(
    vm: AppViewModel,
    startTs: Long,
    deviceId: String,
    onBack: () -> Unit,
) {
    val workouts by vm.workouts.collectAsStateWithLifecycle()
    val row = remember(workouts, startTs, deviceId) {
        workouts.firstOrNull { it.startTs == startTs && it.deviceId == deviceId }
            ?: workouts.firstOrNull { it.startTs == startTs }
    }

    if (row == null) {
        ScreenScaffold(title = null) {
            WorkoutDetailHeader(row = null, onBack = onBack)
            DataPendingNote(
                title = uiString(R.string.workout_detail_missing_title),
                body = uiString(R.string.workout_detail_missing_body),
            )
        }
        return
    }

    // Per-session reads, all keyed on the session window so switching rows re-fetches.
    var hrCurve by remember(row.startTs) { mutableStateOf<List<Double>>(emptyList()) }
    var timeInZone by remember(row.startTs) { mutableStateOf<TimeInZone?>(null) }
    var hrr by remember(row.startTs) { mutableStateOf<HeartRateRecovery.Result?>(null) }
    var steps by remember(row.startTs) { mutableStateOf<Int?>(null) }
    LaunchedEffect(row.startTs, row.endTs) {
        hrCurve = vm.workoutHrBuckets(row.startTs, row.endTs, row.source, row.deviceId).map { it.avgBpm }
        timeInZone = vm.workoutTimeInZone(row.startTs, row.endTs, row.source, row.deviceId)
        steps = if (WorkoutSport.isOnFoot(row.sport)) vm.workoutSteps(row.startTs, row.endTs) else null
        hrr = vm.workoutHeartRateRecovery(row.startTs, row.endTs, row.source, row.deviceId)
    }

    val peers = remember(workouts, row.startTs) { baselinePeers(workouts, row) }
    val zoneSet = remember { vm.hrZoneSet }

    ScreenScaffold(title = null) {
        WorkoutDetailHeader(row = row, onBack = onBack)
        EffortHeadline(row = row, peers = peers)
        WorkoutHrSection(row = row, curve = hrCurve)
        WorkoutZoneSection(row = row, timeInZone = timeInZone, zoneSet = zoneSet, peers = peers)
        WorkoutKeyStatistics(row = row, peers = peers, steps = steps)
        WorkoutRouteExport(row)
        hrr?.let { HeartRateRecoveryCard(it) }
        StrengthWorkout.decode(row.strengthJSON)?.let {
            StrengthDetailCard(it, UnitPrefs.system(LocalContext.current))
        }
        if (!row.notes.isNullOrBlank()) {
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space6)) {
                    Overline(uiString(R.string.workout_detail_notes))
                    Text(row.notes, style = NoopType.body, color = Palette.textPrimary)
                }
            }
        }
    }
}

/** Back chevron, sport glyph, name and time range — the page's own top bar. */
@Composable
private fun WorkoutDetailHeader(row: WorkoutRow?, onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(Metrics.iconButton)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onBack,
                )
                .semantics { contentDescription = uiString(R.string.workout_detail_back) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = null,
                tint = Palette.textPrimary,
                modifier = Modifier.size(Metrics.space24),
            )
        }
        Spacer(Modifier.width(Metrics.space8))
        if (row != null) {
            Icon(
                sportIcon(row.sport),
                contentDescription = null,
                tint = Palette.textPrimary,
                modifier = Modifier.size(Metrics.space24),
            )
            Spacer(Modifier.width(Metrics.space10))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    WorkoutEditing.displaySport(row.sport).uppercase(Locale.getDefault()),
                    style = NoopType.overline.copy(fontSize = 15.sp),
                    color = Palette.textPrimary,
                    maxLines = 1,
                )
                Text(
                    listOfNotNull(
                        timeRangeLabel(row.startTs, row.endTs),
                        hmsDuration(row.durationS ?: (row.endTs - row.startTs).toDouble()),
                    ).joinToString(" · "),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                    maxLines = 1,
                )
            }
            val (srcLabel, srcTint) = row.sourceBadge
            SourceBadge(srcLabel, tint = srcTint)
        }
    }
}

/** The session's Effort, against the same sport's recent average. */
@Composable
private fun EffortHeadline(row: WorkoutRow, peers: List<WorkoutRow>) {
    val strain = row.strain ?: return
    val effortScale = UnitPrefs.effortScale(LocalContext.current)
    val shown = UnitFormatter.effortValue(strain, effortScale)
    val baseline = sessionBaseline(peers.mapNotNull { it.strain })
        ?.let { UnitFormatter.effortValue(it, effortScale) }
    NoopCard(tint = Palette.effortColor) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
            Overline(uiString(R.string.workout_detail_activity_effort))
            MetricHeadline(
                value = String.format(Locale.getDefault(), "%.1f", shown),
                baseline = baseline?.let {
                    uiString(R.string.workout_detail_sport_average, String.format(Locale.getDefault(), "%.1f", it))
                },
                direction = metricDirection(shown, baseline, epsilon = 0.05),
                // A harder session than usual is not "bad"; Effort is neutral, so the arrow stays
                // informational and the tint follows "more effort reads as the positive direction".
                higherIsBetter = true,
            )
        }
    }
}

/** The heart-rate trace over the session, with its duration. */
@Composable
private fun WorkoutHrSection(row: WorkoutRow, curve: List<Double>) {
    if (curve.size < 2) return
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Overline(uiString(R.string.workout_detail_heart_rate), modifier = Modifier.weight(1f))
                hmsDuration(row.durationS ?: (row.endTs - row.startTs).toDouble())?.let {
                    Text(
                        uiString(R.string.workout_detail_duration, it),
                        style = NoopType.captionNumber,
                        color = Palette.textSecondary,
                    )
                }
            }
            LineChart(
                values = curve,
                modifier = Modifier.height(Metrics.chartHeight),
                color = Palette.effortColor,
                fill = true,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                val lo = curve.minOrNull()?.roundToInt() ?: 0
                val hi = curve.maxOrNull()?.roundToInt() ?: 0
                // Reuse the existing Trends labels rather than minting three more translated keys for
                // words the app already ships in every locale.
                HrReadout(uiString(R.string.trends_avg), row.avgHr ?: (curve.sum() / curve.size).roundToInt(), Modifier.weight(1f))
                HrReadout(uiString(R.string.trends_peak), row.maxHr ?: hi, Modifier.weight(1f))
                HrReadout(uiString(R.string.trends_low), lo, Modifier.weight(1f))
            }
            // A manually edited average can disagree with the captured trace while the imported Effort
            // and zone split still describe that original trace. The old detail sheet disclosed that
            // distinction; keep it on the replacement page so the new surface never implies the graph
            // was recomputed from the edited headline number.
            val traceMean = curve.sum() / curve.size
            val captured = row.strain != null || !row.zonesJSON.isNullOrEmpty()
            if (captured && row.avgHr != null && abs(row.avgHr - traceMean) > 3.0) {
                Text(
                    uiString(R.string.l10n_workouts_screen_the_average_above_was_edited_the_0a7881f0),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }
    }
}

@Composable
private fun HrReadout(label: String, bpm: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
        Text(label, style = NoopType.overline, color = Palette.textTertiary)
        Text(
            uiString(R.string.workout_detail_bpm, bpm),
            style = NoopType.number(17f, weight = FontWeight.Bold),
            color = Palette.textPrimary,
        )
    }
}

/**
 * Time in each HR zone, one card per zone, highest first.
 *
 * Zone 0 (below 50% HRmax) is shown rather than dropped: an easy session can spend most of its minutes
 * there, and hiding it rescales the remaining zones so the session reads harder than it was.
 */
@Composable
private fun WorkoutZoneSection(
    row: WorkoutRow,
    timeInZone: TimeInZone?,
    zoneSet: HrZoneSet,
    peers: List<WorkoutRow>,
) {
    // Prefer the imported per-zone split (WHOOP's own numbers) over one derived from raw strap HR.
    val importedPercents = remember(row.zonesJSON) { parseZonePercents(row.zonesJSON) }
    val totalSeconds = row.durationS ?: (row.endTs - row.startTs).toDouble()
    val zoneSeconds: List<Double>? = when {
        importedPercents != null && totalSeconds > 0.0 -> importedPercents.map { totalSeconds * it / 100.0 }
        timeInZone != null -> timeInZone.seconds
        else -> null
    }
    if (zoneSeconds == null || zoneSeconds.sum() <= 0.0) return

    // Below-Zone-1 time only exists on the derived path; an imported split has no such bucket.
    val belowZone1 = if (importedPercents == null) timeInZone?.belowZone1 ?: 0.0 else 0.0
    val counted = zoneSeconds.sum() + belowZone1

    val history = remember(peers) { peers.mapNotNull { parseZonePercents(it.zonesJSON) } }

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Overline(uiString(R.string.l10n_workouts_screen_hr_zones_293d7175), modifier = Modifier.weight(1f))
            Text(
                if (importedPercents != null) uiString(R.string.workout_detail_zones_imported)
                else uiString(R.string.workout_detail_zones_derived),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
        for (z in 5 downTo 1) {
            val secs = zoneSeconds.getOrNull(z - 1) ?: 0.0
            val zone = zoneSet.zones.firstOrNull { it.number == z }
            ZoneRowCard(
                label = uiString(R.string.l10n_live_screen_zone_zone_b8e9c0f9, z),
                span = zone?.let {
                    if (z == 5) uiString(R.string.workout_detail_zone_span_open, it.lower.roundToInt())
                    else uiString(R.string.workout_detail_zone_span, it.lower.roundToInt(), (it.upper - 1).roundToInt())
                } ?: "",
                share = uiString(R.string.workout_detail_percent, sharePercent(secs, counted)),
                duration = hmsDuration(secs).orEmpty(),
                fraction = if (counted > 0.0) (secs / counted).toFloat() else 0f,
                color = Palette.hrZoneColor(z),
                typicalRange = typicalZoneShare(history, z - 1),
                dimmed = secs <= 0.0,
                contentDescription = uiString(
                    R.string.workout_detail_zone_desc,
                    z,
                    sharePercent(secs, counted),
                    hmsDuration(secs).orEmpty(),
                ),
            )
        }
        if (belowZone1 > 0.0) {
            val zone1Lower = zoneSet.zones.firstOrNull { it.number == 1 }?.lower?.roundToInt()
            ZoneRowCard(
                label = uiString(R.string.l10n_live_screen_zone_zone_b8e9c0f9, 0),
                span = zone1Lower?.let { uiString(R.string.workout_detail_zone_span_below, it) } ?: "",
                share = uiString(R.string.workout_detail_percent, sharePercent(belowZone1, counted)),
                duration = hmsDuration(belowZone1).orEmpty(),
                fraction = (belowZone1 / counted).toFloat(),
                color = Palette.textTertiary,
                contentDescription = uiString(
                    R.string.workout_detail_zone_desc,
                    0,
                    sharePercent(belowZone1, counted),
                    hmsDuration(belowZone1).orEmpty(),
                ),
            )
        }
    }
}

private fun sharePercent(part: Double, total: Double): Int =
    if (total <= 0.0) 0 else ((part / total) * 100.0).roundToInt()

/** Calories, average HR, distance and steps against the same sport's 30-day mean where available. */
@Composable
private fun WorkoutKeyStatistics(row: WorkoutRow, peers: List<WorkoutRow>, steps: Int?) {
    val kcal = row.energyKcal
    val avgHr = row.avgHr
    val distanceM = row.distanceM?.takeIf { it > 0.0 }
    // Activity-file imports can carry an authoritative per-session count. Prefer it over a derived strap
    // counter, but retain the latter for on-foot sessions whose row predates the workout.steps column.
    val shownSteps = row.steps ?: steps
    if (kcal == null && avgHr == null && distanceM == null && shownSteps == null) return
    val kcalBase = sessionBaseline(peers.mapNotNull { it.energyKcal })
    val hrBase = sessionBaseline(peers.mapNotNull { it.avgHr?.toDouble() })
    val distanceBase = sessionBaseline(peers.mapNotNull { it.distanceM?.takeIf { m -> m > 0.0 } })
    val stepsBase = sessionBaseline(peers.mapNotNull { it.steps?.toDouble() })
    val units = UnitPrefs.system(LocalContext.current)

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Overline(uiString(R.string.workout_detail_key_statistics), modifier = Modifier.weight(1f))
            if (kcalBase != null || hrBase != null || distanceBase != null || stepsBase != null) {
                Text(
                    uiString(R.string.workout_detail_vs_sport_average),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }
        if (kcal != null || avgHr != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                if (kcal != null) {
                    KeyStatTile(
                        icon = Icons.Filled.LocalFireDepartment,
                        label = uiString(R.string.workout_detail_calories),
                        value = String.format(Locale.getDefault(), "%,d", kcal.roundToInt()),
                        unit = uiString(R.string.workout_detail_cals),
                        delta = kcalBase?.let {
                            uiString(R.string.workout_detail_cals_delta, abs(kcal - it).roundToInt())
                        },
                        direction = metricDirection(kcal, kcalBase, epsilon = 5.0),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (avgHr != null) {
                    KeyStatTile(
                        icon = Icons.Filled.MonitorHeart,
                        label = uiString(R.string.workout_detail_avg_hr_label),
                        value = avgHr.toString(),
                        unit = uiString(R.string.workout_detail_bpm_unit),
                        delta = hrBase?.let {
                            uiString(R.string.workout_detail_bpm, abs(avgHr - it).roundToInt())
                        },
                        direction = metricDirection(avgHr.toDouble(), hrBase, epsilon = 1.0),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (distanceM != null || shownSteps != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                if (distanceM != null) {
                    KeyStatTile(
                        icon = Icons.Filled.Route,
                        label = uiString(R.string.l10n_live_screen_distance_42320809),
                        value = UnitFormatter.distanceFromKilometers(distanceM / 1000.0, units),
                        delta = distanceBase?.let {
                            UnitFormatter.distanceFromKilometers(abs(distanceM - it) / 1000.0, units)
                        },
                        direction = metricDirection(distanceM, distanceBase, epsilon = 10.0),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (shownSteps != null) {
                    KeyStatTile(
                        icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                        label = uiString(R.string.l10n_health_screen_steps_cdde4f20),
                        value = String.format(Locale.getDefault(), "%,d", shownSteps),
                        delta = stepsBase?.let {
                            String.format(Locale.getDefault(), "%,d", abs(shownSteps - it).roundToInt())
                        },
                        direction = metricDirection(shownSteps.toDouble(), stepsBase, epsilon = 10.0),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Restore the route export carried by the superseded detail sheet. Nothing is uploaded: the existing
 *  [RouteExportShare] writes a temporary on-device GPX/FIT file and opens Android's share chooser. */
@Composable
private fun WorkoutRouteExport(row: WorkoutRow) {
    val encoded = row.routePolyline ?: return
    val track = remember(encoded) { RouteMath.decode(encoded) }
    if (track.size < 2) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Overline(uiString(R.string.l10n_live_screen_distance_42320809))
            Text(
                uiString(R.string.l10n_settings_screen_export_0a116345),
                style = NoopType.subhead,
                color = Palette.textPrimary,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                NoopButton(
                    text = "GPX",
                    kind = NoopButtonKind.Secondary,
                    modifier = Modifier.weight(1f),
                    onClick = { scope.launch { RouteExportShare.share(context, RouteExport.Format.GPX, track, row) } },
                )
                NoopButton(
                    text = "FIT",
                    kind = NoopButtonKind.Secondary,
                    modifier = Modifier.weight(1f),
                    onClick = { scope.launch { RouteExportShare.share(context, RouteExport.Format.FIT, track, row) } },
                )
            }
        }
    }
}
