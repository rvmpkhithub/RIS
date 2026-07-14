package com.ris.imagedistributor.ui.receivers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ris.imagedistributor.data.local.ReceiverChannel
import com.ris.imagedistributor.data.local.ReceiverWithSchedules
import com.ris.imagedistributor.data.local.channelOrDefault
import com.ris.imagedistributor.di.AppContainer
import com.ris.imagedistributor.ui.theme.GoldBorder
import kotlinx.coroutines.launch

/**
 * Top-level Receivers tab — holds the list/edit route (mirrors AppRouter.kt's pattern, no nav
 * library) and a single shared ReceiversViewModel across both sub-screens.
 */
@Composable
fun ReceiversTab(container: AppContainer) {
    val viewModel: ReceiversViewModel = viewModel(factory = ReceiversViewModel.factory(container))
    var route by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.Saver<ReceiversRoute, String>(
        save = { r -> when (r) { is ReceiversRoute.List -> "list"; is ReceiversRoute.Edit -> "edit:${r.receiverId ?: -1}" } },
        restore = { s ->
            // [Review][Patch] a malformed/foreign saved-state string (realistic given this app's
            // sideload-APK-update model) must not crash route restoration — fall back to List.
            runCatching {
                if (s == "list") ReceiversRoute.List
                else {
                    val id = s.removePrefix("edit:").toLong()
                    ReceiversRoute.Edit(if (id == -1L) null else id)
                }
            }.getOrDefault(ReceiversRoute.List)
        },
    )) { mutableStateOf<ReceiversRoute>(ReceiversRoute.List) }

    // [Review][Patch] without this, system Back exits the whole app instead of just backing out
    // of the Edit sub-route — reproduced live during this story's own on-device verification.
    BackHandler(enabled = route is ReceiversRoute.Edit) {
        route = ReceiversRoute.List
    }

    when (val current = route) {
        is ReceiversRoute.List -> ReceiversListScreen(
            viewModel = viewModel,
            onAddNew = { route = ReceiversRoute.Edit(null) },
            onEditReceiver = { id -> route = ReceiversRoute.Edit(id) },
        )
        is ReceiversRoute.Edit -> {
            val receivers by viewModel.receivers.collectAsState()
            ReceiverEditScreen(
                viewModel = viewModel,
                receiverId = current.receiverId,
                existing = current.receiverId?.let { id -> receivers?.find { it.receiver.id == id } },
                stillLoading = current.receiverId != null && receivers == null,
                onDone = { route = ReceiversRoute.List },
            )
        }
    }
}

@Composable
internal fun ReceiversListScreen(
    viewModel: ReceiversViewModel,
    onAddNew: () -> Unit,
    onEditReceiver: (Long) -> Unit,
) {
    val receiverList = viewModel.receivers.collectAsState().value

    Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        // [Review][Patch] null (not yet loaded) previously collapsed into the same branch as a
        // genuinely empty list — briefly flashed "No receivers yet" on cold load, defeating the
        // whole point of receivers being nullable in the first place.
        if (receiverList == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else if (receiverList.isEmpty()) {
            Card(
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.5.dp, GoldBorder),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Text(
                    text = "No receivers yet — add one to start sending.",
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // [Review][Patch] reserve space for the bottom "Add receiver" button so it
                // doesn't overlap/obscure the last row(s).
                contentPadding = PaddingValues(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(receiverList, key = { it.receiver.id }) { entry ->
                    ReceiverRow(
                        entry = entry,
                        viewModel = viewModel,
                        onClick = { onEditReceiver(entry.receiver.id) },
                    )
                }
            }
        }

        Button(
            onClick = onAddNew,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            Text("Add receiver")
        }
    }
}

@Composable
private fun ReceiverRow(entry: ReceiverWithSchedules, viewModel: ReceiversViewModel, onClick: () -> Unit) {
    val receiver = entry.receiver
    var showConfirm by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
            showConfirm = true
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = {
                showConfirm = false
                scope.launch { dismissState.reset() }
            },
            title = { Text("Remove ${receiver.name}?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                Button(onClick = {
                    showConfirm = false
                    scope.launch {
                        // [Review][Patch] reset before attempting — otherwise a stale error from
                        // a previous failed attempt could flash even on a now-successful retry.
                        deleteError = false
                        // [Review][Patch] the delete result was previously discarded — a failed
                        // delete left the row visually swiped-away with no reset and no feedback.
                        val success = viewModel.deleteReceiver(receiver.id)
                        if (!success) {
                            deleteError = true
                            dismissState.reset()
                        }
                    }
                }) { Text("Remove") }
            },
            dismissButton = {
                Button(onClick = {
                    showConfirm = false
                    scope.launch { dismissState.reset() }
                }) { Text("Cancel") }
            },
        )
    }

    Column {
        SwipeToDismissBox(
            state = dismissState,
            backgroundContent = { },
        ) {
            Card(
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    val channelLabel = if (receiver.channelOrDefault() == ReceiverChannel.WHATSAPP) "WhatsApp" else "Email"
                    Text(text = "${receiver.name} · $channelLabel", style = MaterialTheme.typography.titleMedium)
                    // DESIGN.md#Components — receiver-row shows a schedule-count summary, not
                    // the full time list (that lives on the Edit screen). A schedule-less
                    // receiver reads "Uses master schedule" rather than "0×/day" — [Sprint Change
                    // Proposal 2026-07-12], since zero is now a valid, intentional state.
                    val scheduleSummary = if (entry.scheduleTimes.isEmpty()) "Uses master schedule" else "${entry.scheduleTimes.size}×/day"
                    Text(
                        text = scheduleSummary,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    // [Review][Patch] a receiver migrated from the old single-schedule model (or
                    // otherwise left below the minimum) is otherwise invisible in the list — flag
                    // it so the operator knows to open Edit and add more times. A genuinely empty
                    // schedule is valid (falls back to the master schedule) and must not trigger
                    // this warning — only a partially filled list (1-3 times) does.
                    if (entry.scheduleTimes.isNotEmpty() && entry.scheduleTimes.size < MIN_SCHEDULE_TIMES) {
                        Text(
                            text = "Needs ${MIN_SCHEDULE_TIMES - entry.scheduleTimes.size} more schedule time(s)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                // [Review][Patch] DESIGN.md's receiver-row spec calls for a divider using
                // {colors.outline}, previously unused.
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
        if (deleteError) {
            Text(
                text = "Couldn't remove ${receiver.name} — please try again.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )
        }
    }
}
