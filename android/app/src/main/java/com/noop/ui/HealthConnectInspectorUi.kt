package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.ingest.HealthConnectInspector

/**
 * The result of [HealthConnectInspector.inspect]: one card per app that wrote to Health Connect in the
 * window, with what it sent and which NOOP scores that data could carry. [result] null = still reading.
 */
@Composable
internal fun HealthConnectInspectorDialog(
    result: List<HealthConnectInspector.SourceSummary>?,
    onDismiss: () -> Unit,
    // Apps already registered as a band, and the add action for one that is not (null hides it).
    addedPackages: Set<String> = emptySet(),
    onAdd: ((HealthConnectInspector.SourceSummary) -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.surfaceOverlay,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
                Text(uiString(R.string.hc_inspect_title), style = NoopType.headline, color = Palette.textPrimary)
                Text(uiString(R.string.hc_inspect_window), style = NoopType.caption, color = Palette.textSecondary)
            }
        },
        text = {
            when {
                result == null -> Box(Modifier.fillMaxWidth().padding(Metrics.space24), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Palette.textSecondary)
                }
                result.isEmpty() -> Text(uiString(R.string.hc_inspect_empty), style = NoopType.body, color = Palette.textSecondary)
                else -> LazyColumn(
                    modifier = Modifier.heightIn(max = Metrics.dialogScrollableMaxHeight),
                    verticalArrangement = Arrangement.spacedBy(Metrics.space8),
                ) {
                    items(result, key = { it.packageName }) { s ->
                        HcSourceCard(s, added = s.packageName in addedPackages, onAdd = onAdd?.let { add -> { add(s) } })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(uiString(R.string.hc_inspect_close), color = Palette.textPrimary)
            }
        },
    )
}

@Composable
private fun HcSourceCard(s: HealthConnectInspector.SourceSummary, added: Boolean = false, onAdd: (() -> Unit)? = null) {
    val ready = HealthConnectInspector.readiness(s)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Metrics.cornerSm))
            .background(Palette.surfaceInset)
            .semantics(mergeDescendants = true) {}
            .padding(Metrics.space12),
        verticalArrangement = Arrangement.spacedBy(Metrics.space6),
    ) {
        Text(
            HealthConnectInspector.appName(s.packageName),
            style = NoopType.subhead.copy(fontWeight = FontWeight.SemiBold), color = Palette.textPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        HcLine(
            uiString(R.string.hc_inspect_hr),
            if (s.hrSamples == 0) uiString(R.string.hc_inspect_none)
            else uiString(R.string.hc_inspect_hr_detail, s.hrSamples, gapText(s.hrTypicalGapSec), s.hrDays,
                HealthConnectInspector.WINDOW_DAYS.toInt()),
        )
        HcLine(
            uiString(R.string.hc_inspect_sleep),
            if (s.sleepSessions == 0) uiString(R.string.hc_inspect_none)
            else uiString(R.string.hc_inspect_sleep_detail, s.sleepSessions, s.sleepWithStages),
        )
        HcLine(uiString(R.string.hc_inspect_hrv), countText(s.hrvReadings))
        HcLine(uiString(R.string.hc_inspect_rhr), countText(s.restingHrReadings))
        HcLine(uiString(R.string.hc_inspect_spo2), countText(s.spo2Readings))
        HcLine(uiString(R.string.hc_inspect_resp), countText(s.respReadings))
        Column(Modifier.padding(top = Metrics.space4), verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
            HcReady(ready.effort, uiString(R.string.hc_inspect_ready_effort))
            HcReady(ready.sleep, uiString(R.string.hc_inspect_ready_sleep))
            HcReady(ready.recovery, uiString(R.string.hc_inspect_ready_recovery))
        }
        // Only an app that sent heart rate or sleep is worth adding as a band.
        if (added) {
            Text(uiString(R.string.hc_band_added), style = NoopType.caption, color = Palette.textSecondary)
        } else if (onAdd != null && (s.hrSamples > 0 || s.sleepSessions > 0)) {
            TextButton(onClick = onAdd, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(uiString(R.string.hc_band_add), style = NoopType.subhead.copy(fontWeight = FontWeight.SemiBold),
                    color = Palette.accent)
            }
        }
    }
}

@Composable
private fun HcLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        Text(label, style = NoopType.caption, color = Palette.textSecondary, modifier = Modifier.weight(1f))
        Text(value, style = NoopType.captionNumber, color = Palette.textPrimary, maxLines = 2)
    }
}

/** A readiness line: a status dot (positive / neutral) and what the data would support. */
@Composable
private fun HcReady(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.space6)) {
        Box(Modifier.size(Metrics.legendSwatch).clip(CircleShape)
            .background(if (ok) Palette.statusPositive else Palette.hairlineStrong))
        Text(text, style = NoopType.caption, color = if (ok) Palette.textPrimary else Palette.textTertiary)
    }
}

@Composable
private fun countText(n: Int): String =
    if (n == 0) uiString(R.string.hc_inspect_none) else uiString(R.string.hc_inspect_count, n)

@Composable
private fun gapText(sec: Long?): String = when {
    sec == null -> "—"
    sec < 120 -> uiString(R.string.hc_inspect_seconds, sec.toInt())
    else -> uiString(R.string.hc_inspect_minutes, (sec / 60).toInt())
}
