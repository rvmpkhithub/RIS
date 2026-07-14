package com.ris.imagedistributor.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ris.imagedistributor.di.AppContainer
import com.ris.imagedistributor.ui.components.ScheduleTimeListEditor
import com.ris.imagedistributor.ui.components.addScheduleTime

/** Same minimum as a receiver's own schedule — but unlike a receiver's, this one has no "or zero" exception. */
private const val MIN_MASTER_SCHEDULE_TIMES = 4

/**
 * DESIGN.md#Components (retention-row: corner: '{rounded.sm}') — label-left/value-right row.
 * EXPERIENCE.md#Component Patterns — "Tap opens a numeric picker (days), not a slider — the
 * value only ever needs coarse adjustment." Retention (Story 3.2) and the master schedule
 * (Story 2.3, the app-wide default schedule used by any receiver with none of its own) are
 * Settings' two sections.
 */
@Composable
fun SettingsScreen(container: AppContainer) {
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val retentionDays by viewModel.retentionDays.collectAsState()
    val masterScheduleTimes by viewModel.masterScheduleTimes.collectAsState()
    var showRetentionDialog by remember { mutableStateOf(false) }
    var showMasterScheduleDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(text = "Settings", style = MaterialTheme.typography.headlineSmall)

        retentionDays?.let { days ->
            Card(
                shape = MaterialTheme.shapes.small,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).clickable { showRetentionDialog = true },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "Retention period", style = MaterialTheme.typography.bodyLarge)
                    Text(text = "$days days", style = MaterialTheme.typography.bodyLarge)
                }
            }

            if (showRetentionDialog) {
                RetentionPickerDialog(
                    currentDays = days,
                    onDismiss = { showRetentionDialog = false },
                    onSave = { newDays, onResult -> viewModel.onSave(newDays, onResult) },
                )
            }
        }

        masterScheduleTimes?.let { times ->
            Card(
                shape = MaterialTheme.shapes.small,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).clickable { showMasterScheduleDialog = true },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "Master schedule", style = MaterialTheme.typography.bodyLarge)
                    Text(text = "${times.size} times/day", style = MaterialTheme.typography.bodyLarge)
                }
            }

            if (showMasterScheduleDialog) {
                MasterSchedulePickerDialog(
                    currentTimes = times,
                    onDismiss = { showMasterScheduleDialog = false },
                    onSave = { newTimes, onResult -> viewModel.onSaveMasterSchedule(newTimes, onResult) },
                )
            }
        }
    }
}

/**
 * Reuses `ScheduleTimeListEditor` verbatim (same component `ReceiverEditScreen` uses) — the one
 * behavioral difference is the validation predicate: this one has no "or zero" exception, since
 * there is nothing for the master schedule itself to fall back to. [DESIGN.md#Components —
 * master-schedule-list, EXPERIENCE.md#Component Patterns — Schedule time list]
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MasterSchedulePickerDialog(
    currentTimes: List<Int>,
    onDismiss: () -> Unit,
    onSave: (List<Int>, (Boolean) -> Unit) -> Unit,
) {
    val scheduleTimes = remember { mutableStateListOf<Int>().apply { addAll(currentTimes) } }
    var showTimePicker by remember { mutableStateOf(false) }
    var duplicateTimeMessage by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    if (showTimePicker) {
        val timeState = rememberTimePickerState(initialHour = 9, initialMinute = 0, is24Hour = true)
        TimePickerDialog(
            title = { Text("Add schedule time") },
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                Button(onClick = {
                    val newTime = timeState.hour * 60 + timeState.minute
                    duplicateTimeMessage = !addScheduleTime(scheduleTimes, newTime)
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                Button(onClick = { showTimePicker = false }) { Text("Cancel") }
            },
        ) {
            TimePicker(state = timeState)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Master schedule") },
        text = {
            ScheduleTimeListEditor(
                scheduleTimes = scheduleTimes,
                onAddTimeClick = { showTimePicker = true },
                duplicateTimeMessage = duplicateTimeMessage,
                error = error,
                label = "Master schedule times",
            )
        },
        confirmButton = {
            Button(onClick = {
                // Unlike a receiver's own schedule, zero is never valid here.
                if (scheduleTimes.size < MIN_MASTER_SCHEDULE_TIMES) {
                    error = "Add at least $MIN_MASTER_SCHEDULE_TIMES schedule times."
                    return@Button
                }
                onSave(scheduleTimes.toList()) { success ->
                    if (success) onDismiss() else error = "Couldn't save — please try again."
                }
            }) { Text("Save") }
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun RetentionPickerDialog(
    currentDays: Int,
    onDismiss: () -> Unit,
    onSave: (Int, (Boolean) -> Unit) -> Unit,
) {
    var text by remember { mutableStateOf(currentDays.toString()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Retention period") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    // [Review][Patch] clear a stale error the moment the user starts correcting
                    // their input — previously it stayed on screen until the next Save tap, even
                    // once the field held a now-valid value.
                    onValueChange = { text = it.filter(Char::isDigit); error = null },
                    label = { Text("Days") },
                    isError = error != null,
                    singleLine = true,
                )
                error?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val days = text.toIntOrNull()
                if (days == null || days <= 0) {
                    error = "Enter a valid number of days."
                    return@Button
                }
                onSave(days) { success ->
                    if (success) onDismiss() else error = "Couldn't save — please try again."
                }
            }) { Text("Save") }
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
