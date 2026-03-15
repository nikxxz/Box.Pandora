package com.example.boxpandora.ui.settings

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.example.boxpandora.ui.common.AppDialog
import com.example.boxpandora.ui.settings.viewmodel.MaintenanceProgressState
import com.example.boxpandora.ui.settings.viewmodel.WorkerProgress
import com.example.boxpandora.ui.theme.ModalTokens
import com.example.boxpandora.ui.theme.boxPandoraModalTokens

private val AccentGreen = Color(0xFF8AE0A6)

private fun WorkInfo.State.isTerminal() =
    this == WorkInfo.State.SUCCEEDED ||
    this == WorkInfo.State.FAILED ||
    this == WorkInfo.State.CANCELLED

/**
 * Dialog shown while a maintenance action's background workers run.
 * Tap "Close" / "Done" to dismiss (workers keep running).
 * Tap "Cancel Work" to stop the workers and dismiss.
 */
@Composable
fun MaintenanceProgressDialog(
    state: MaintenanceProgressState.Active,
    onDismiss: () -> Unit,
    onCancel: () -> Unit
) {
    val tokens = boxPandoraModalTokens()
    val allFinished = state.workers.all { it.state.isTerminal() }
    val anyFailed   = state.workers.any { it.state == WorkInfo.State.FAILED || it.state == WorkInfo.State.CANCELLED }

    AppDialog(
        onDismiss = onDismiss,
        maxWidth = 460.dp
    ) {
        // ── Header ────────────────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text  = state.actionLabel,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = tokens.titleText
            )
            Text(
                text  = when {
                    !allFinished -> "Workers are running in the background\u2026"
                    anyFailed    -> "Finished with errors — see details below"
                    else         -> "All workers completed successfully"
                },
                style = MaterialTheme.typography.bodySmall,
                color = tokens.secondaryText
            )
        }

        // ── Per-worker rows ───────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.workers.forEach { worker ->
                WorkerProgressRow(worker = worker, tokens = tokens)
            }
        }

        // ── Action buttons ────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!allFinished) {
                TextButton(
                    onClick = onCancel,
                    colors  = ButtonDefaults.textButtonColors(contentColor = tokens.destructiveAccent)
                ) {
                    Text(
                        "Cancel Work",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium)
                    )
                }
            } else {
                Spacer(Modifier.width(1.dp))
            }
            Button(
                onClick = onDismiss,
                colors  = ButtonDefaults.buttonColors(
                    containerColor = tokens.selectedAccent,
                    contentColor   = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text  = if (allFinished) "Done" else "Close",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }
    }
}

@Composable
private fun WorkerProgressRow(worker: WorkerProgress, tokens: ModalTokens) {
    val isRunning = worker.state == WorkInfo.State.RUNNING
    val isDone    = worker.state == WorkInfo.State.SUCCEEDED
    val isFailed  = worker.state == WorkInfo.State.FAILED || worker.state == WorkInfo.State.CANCELLED

    val stateColor = when {
        isDone    -> AccentGreen
        isFailed  -> tokens.destructiveAccent
        isRunning -> tokens.selectedAccent
        else      -> tokens.secondaryText.copy(alpha = 0.45f)
    }

    val stateLabel = when (worker.state) {
        WorkInfo.State.RUNNING    -> if (worker.total > 0) "${worker.processed} / ${worker.total}" else "Running\u2026"
        WorkInfo.State.SUCCEEDED  -> "Done"
        WorkInfo.State.FAILED     -> "Failed"
        WorkInfo.State.CANCELLED  -> "Cancelled"
        WorkInfo.State.BLOCKED    -> "Waiting"
        else                      -> "Queued"
    }

    Row(
        modifier            = Modifier.fillMaxWidth(),
        verticalAlignment   = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // State indicator (16dp — spinner when running, icon otherwise)
        Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            when {
                isRunning -> CircularProgressIndicator(
                    modifier   = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color      = tokens.selectedAccent,
                    trackColor = tokens.cardBorder
                )
                isDone    -> Icon(
                    Icons.Default.CheckCircle, null,
                    tint     = AccentGreen,
                    modifier = Modifier.size(16.dp)
                )
                isFailed  -> Icon(
                    Icons.Default.Error, null,
                    tint     = tokens.destructiveAccent,
                    modifier = Modifier.size(16.dp)
                )
                else      -> Icon(
                    Icons.Default.Schedule, null,
                    tint     = tokens.secondaryText.copy(alpha = 0.4f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        // Name + count + optional progress bar
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text  = worker.displayName,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = tokens.bodyText
                )
                Text(
                    text  = stateLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = stateColor
                )
            }

            when {
                // Determinate bar — we know total
                (isRunning || isDone) && worker.total > 0 -> {
                    val pct = if (isDone) 1f else worker.processed.toFloat() / worker.total
                    LinearProgressIndicator(
                        progress  = { pct },
                        modifier  = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(999.dp)),
                        color      = stateColor,
                        trackColor = tokens.cardBorder
                    )
                }
                // Indeterminate bar — running but no count yet
                isRunning -> {
                    LinearProgressIndicator(
                        modifier  = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(999.dp)),
                        color      = tokens.selectedAccent,
                        trackColor = tokens.cardBorder
                    )
                }
                // Completed full bar (no total was ever reported)
                isDone -> {
                    LinearProgressIndicator(
                        progress  = { 1f },
                        modifier  = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(999.dp)),
                        color      = AccentGreen,
                        trackColor = tokens.cardBorder
                    )
                }
                else -> Spacer(Modifier.height(3.dp))
            }
        }
    }
}
