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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ris.imagedistributor.di.AppContainer

/**
 * DESIGN.md#Components (retention-row: corner: '{rounded.sm}') — label-left/value-right row.
 * EXPERIENCE.md#Component Patterns — "Tap opens a numeric picker (days), not a slider — the
 * value only ever needs coarse adjustment." This is Settings' only content (Story 3.2).
 */
@Composable
fun SettingsScreen(container: AppContainer) {
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val retentionDays by viewModel.retentionDays.collectAsState()
    var showDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(text = "Settings", style = MaterialTheme.typography.headlineSmall)

        retentionDays?.let { days ->
            Card(
                shape = MaterialTheme.shapes.small,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).clickable { showDialog = true },
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

            if (showDialog) {
                RetentionPickerDialog(
                    currentDays = days,
                    onDismiss = { showDialog = false },
                    onSave = { newDays, onResult -> viewModel.onSave(newDays, onResult) },
                )
            }
        }
    }
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
