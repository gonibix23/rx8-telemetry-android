package com.gonibix.rx8.capabilities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.ui.theme.AccentColor
import com.gonibix.rx8.ui.theme.CardColor
import com.gonibix.rx8.ui.theme.MintColor
import com.gonibix.rx8.ui.theme.MutedColor
import com.gonibix.rx8.ui.theme.WarmWhite
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A frozen preview only. Capturing and document-provider side effects belong to the parent. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CapabilityReportScreen(
    state: CapabilityReportUiState,
    canCaptureLatest: Boolean,
    onCaptureLatest: () -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val report = state.report
    val busy = state.isAwaitingDestination || state.isExporting
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("capability_report_screen"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            TextButton(onClick = onBack, modifier = Modifier.testTag("capability_back")) { Text("Back to Adapter") }
            Text("CAPTURED VIEW · LOCAL", color = MintColor, style = MaterialTheme.typography.labelMedium)
            Text("Vehicle capabilities", style = MaterialTheme.typography.headlineMedium)
            Text("Review what the ECU advertises and the latest displayed readings. Capturing sends no additional requests.", color = MutedColor, style = MaterialTheme.typography.bodySmall)
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onCaptureLatest, enabled = canCaptureLatest && !busy, modifier = Modifier.testTag("capability_capture")) {
                    Text(if (report == null) "Capture snapshot" else "Capture latest")
                }
                OutlinedButton(onClick = onExport, enabled = report != null && !busy, modifier = Modifier.testTag("capability_export")) { Text("Save JSON report") }
            }
            if (!canCaptureLatest) {
                Text(
                    if (report == null) "No completed vehicle discovery is available. Return to Adapter and choose Read live data."
                    else "A new capture is unavailable. The captured preview below is unchanged.",
                    color = MutedColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("capability_capture_unavailable"),
                )
            }
            if (state.isAwaitingDestination) Text("Choose a location for the report…", color = MutedColor, modifier = Modifier.testTag("capability_destination_pending"))
            if (state.isExporting) {
                Text("Saving report…", color = MutedColor, modifier = Modifier.testTag("capability_exporting"))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("capability_error")) }
            state.message?.let { Text(it, color = MintColor, modifier = Modifier.testTag("capability_message")) }
        }
        item {
            Text(
                "The report includes capture time, ECU addresses and displayed readings. It excludes VINs, Bluetooth addresses, vehicle/profile IDs, names and raw logs. Nothing is uploaded.",
                color = MutedColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("capability_privacy"),
            )
        }
        if (report != null) {
            item { SnapshotSummary(report) }
            item {
                Text(
                    "Advertised support does not prove a successful reading. These are the latest displayed states and may include initial No data placeholders. This snapshot is not a full-session, latency or battery test.",
                    color = MutedColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("capability_snapshot_limits"),
                )
            }
            report.ecus.forEach { ecu ->
                item(key = "ecu_${ecu.address}") { EcuCapabilities(ecu) }
                items(ecu.metrics, key = { "${ecu.address}_${it.metric.name}" }) { metric ->
                    MetricSnapshot(ecu.address, metric, report.phase == ObdPhase.STOPPED || report.phase == ObdPhase.ERROR)
                }
            }
            item {
                Text("Report ID: ${report.reportId}", color = MutedColor, style = MaterialTheme.typography.bodySmall)
                Text("This preview is temporary. Save the report to keep a copy; it cannot restore the app.", color = MutedColor, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            item {
                Text("No snapshot captured yet.", color = MutedColor, modifier = Modifier.testTag("capability_empty"))
            }
        }
    }
}

@Composable
private fun SnapshotSummary(report: CapabilityReport) {
    Surface(color = CardColor, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().testTag("capability_summary")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Captured snapshot", style = MaterialTheme.typography.titleMedium)
            Text(CaptureTime.format(Instant.ofEpochMilli(report.capturedAtEpochMillis)), color = MutedColor, modifier = Modifier.testTag("capability_capture_time"))
            Text(report.phase.captureLabel(), color = if (report.phase == ObdPhase.LIVE) MintColor else AccentColor, modifier = Modifier.testTag("capability_phase"))
            Text("Protocol: ${report.protocolLabel ?: "Unknown"}", modifier = Modifier.testTag("capability_protocol"))
            Text("Acquisition elapsed: ${formatDuration(report.elapsedMillis)}", modifier = Modifier.testTag("capability_elapsed"))
            val rate = report.effectiveResponsesPerSecond?.takeIf { it.isFinite() && it >= 0 }
            Text("Successful ECU responses: ${rate?.let { String.format(Locale.ROOT, "%.2f/s", it) } ?: "Not available"}", modifier = Modifier.testTag("capability_rate"))
            Text("Rate shown by acquisition at this snapshot; it combines sensors and ECUs and is not per-sensor sampling frequency.", color = MutedColor, style = MaterialTheme.typography.bodySmall)
            Text("App ${report.appVersion} · Android API ${report.androidApiLevel}", color = MutedColor, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EcuCapabilities(ecu: CapabilityEcu) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("ECU ${ecu.address}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("capability_ecu_${ecu.address}"))
        Text("${ecu.advertisedPids.size} advertised Mode 01 PIDs", color = MintColor)
        Text(
            if (ecu.advertisedPids.isEmpty()) "No Mode 01 PIDs advertised." else ecu.advertisedPids.joinToString("  ") { String.format(Locale.ROOT, "%02X", it) },
            color = MutedColor,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("capability_pids_${ecu.address}"),
        )
        val otherPids = ecu.advertisedPids.filter { pid -> ObdMetric.entries.none { it.pid == pid } }
        if (otherPids.isNotEmpty()) {
            Text(
                "Advertised but not decoded by this app: ${otherPids.joinToString(", ") { String.format(Locale.ROOT, "%02X", it) }}.",
                color = MutedColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("capability_other_pids_${ecu.address}"),
            )
        }
        Text("Six implemented metrics · latest displayed state", color = MutedColor, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun MetricSnapshot(ecuAddress: String, metric: CapabilityMetric, retained: Boolean) {
    val reading = metric.reading.takeIf { metric.advertised }
    val status = reading?.status?.let { if (retained && it == ObdReadingStatus.VALID) ObdReadingStatus.STALE else it }
    val value = reading?.value?.takeIf { it.isFinite() && status in setOf(ObdReadingStatus.VALID, ObdReadingStatus.STALE) }
    val suffix = "${ecuAddress}_${metric.metric.name.lowercase(Locale.ROOT)}"
    Surface(color = CardColor, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(metric.metric.label, style = MaterialTheme.typography.titleSmall)
            Text(
                "PID ${String.format(Locale.ROOT, "%02X", metric.metric.pid)} · ${if (metric.advertised) "Advertised by ECU" else "Not advertised by ECU"}",
                color = MutedColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("capability_support_$suffix"),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "${value?.let { formatValue(metric.metric, it) } ?: "—"} ${metric.metric.unit}",
                    color = if (status == ObdReadingStatus.STALE) AccentColor else WarmWhite,
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f).testTag("capability_value_$suffix"),
                )
                Text(
                    when {
                        !metric.advertised -> "Not advertised"
                        status == ObdReadingStatus.VALID && value == null -> "No valid value"
                        else -> status.captureLabel()
                    },
                    color = if (status == ObdReadingStatus.VALID && value != null) MintColor else MutedColor,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f).testTag("capability_status_$suffix"),
                )
            }
            if (metric.advertised) {
                val age = reading?.ageMillis?.takeIf { it >= 0 }
                Text(
                    if (age == null) "Reading age unavailable" else "${if (retained) "Age at acquisition stop" else "Age at snapshot"}: ${String.format(Locale.ROOT, "%.1f s", age / 1_000.0)}",
                    color = MutedColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("capability_age_$suffix"),
                )
            }
        }
    }
}

private val CaptureTime = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC)
private fun formatDuration(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1_000
    return String.format(Locale.ROOT, "%d min %02d s", seconds / 60, seconds % 60)
}
private fun formatValue(metric: ObdMetric, value: Double): String =
    String.format(Locale.ROOT, if (metric == ObdMetric.THROTTLE_POSITION || metric == ObdMetric.ENGINE_LOAD) "%.1f" else "%.0f", value)
private fun ObdPhase.captureLabel(): String = when (this) {
    ObdPhase.LIVE -> "Live acquisition at capture"
    ObdPhase.STOPPED -> "Acquisition stopped · retained readings"
    ObdPhase.ERROR -> "Acquisition error · retained readings"
    ObdPhase.IDLE -> "Acquisition not started"
    ObdPhase.INITIALIZING -> "Adapter initialization incomplete"
    ObdPhase.DISCOVERING -> "Capability discovery incomplete"
}
private fun ObdReadingStatus?.captureLabel(): String = when (this) {
    ObdReadingStatus.VALID -> "Valid at capture"
    ObdReadingStatus.STALE -> "Stale · last value"
    ObdReadingStatus.NO_DATA -> "No data"
    ObdReadingStatus.UNSUPPORTED -> "Unsupported"
    ObdReadingStatus.TIMEOUT -> "Timed out"
    ObdReadingStatus.PARSE_ERROR -> "Unreadable response"
    ObdReadingStatus.ADAPTER_ERROR -> "Adapter error"
    null -> "No displayed reading"
}
