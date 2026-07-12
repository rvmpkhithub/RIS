package com.ris.imagedistributor.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.ris.imagedistributor.data.local.DeliveryRecord
import com.ris.imagedistributor.data.local.ReceiverWithSchedules
import com.ris.imagedistributor.di.AppContainer
import com.ris.imagedistributor.ui.theme.GoldBorder
import com.ris.imagedistributor.ui.theme.SuccessMuted
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * DESIGN.md#Components (dashboard-entry-row) — 40dp thumbnail leading, timestamp in
 * {typography.label}, "Sent" in {colors.success-muted} trailing (text tint, never a badge).
 * EXPERIENCE.md#Component Patterns — dropdown/segmented receiver picker at the top.
 * Self-contained viewModel creation, mirroring ReceiversTab (no sub-route needed here).
 */
@Composable
fun DashboardScreen(container: AppContainer) {
    val viewModel: DashboardViewModel = viewModel(factory = DashboardViewModel.factory(container))
    val receivers by viewModel.receivers.collectAsState()
    val selectedReceiverId by viewModel.selectedReceiverId.collectAsState()
    val history by viewModel.history.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(text = "Dashboard", style = MaterialTheme.typography.headlineSmall)

        if (receivers == null) {
            Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (receivers!!.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                Card(
                    shape = MaterialTheme.shapes.large,
                    border = BorderStroke(1.5.dp, GoldBorder),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                ) {
                    Text(
                        text = "No receivers yet — add one to see their delivery history.",
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        } else {
            ReceiverPicker(
                receivers = receivers!!,
                selectedReceiverId = selectedReceiverId,
                onSelect = viewModel::onSelectReceiver,
            )

            when {
                history == null -> Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                history!!.isEmpty() -> Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                    Text("Nothing sent to this receiver yet.")
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().weight(1f).padding(top = 16.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(history!!, key = { it.transmissionId }) { record ->
                        DashboardEntryRow(record = record, container = container)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceiverPicker(
    receivers: List<ReceiverWithSchedules>,
    selectedReceiverId: Long?,
    onSelect: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = receivers.firstOrNull { it.receiver.id == selectedReceiverId }?.receiver?.name ?: ""

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    ) {
        TextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Receiver") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            receivers.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.receiver.name) },
                    onClick = {
                        onSelect(entry.receiver.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun DashboardEntryRow(record: DeliveryRecord, container: AppContainer) {
    val formatter = remember {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault())
    }

    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                model = container.imageRepository.resolveFile(record.image.filePath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(40.dp).clip(MaterialTheme.shapes.small),
            )
            Text(
                text = formatter.format(record.sentAt),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Sent",
                style = MaterialTheme.typography.labelMedium,
                color = SuccessMuted,
            )
        }
    }
}
