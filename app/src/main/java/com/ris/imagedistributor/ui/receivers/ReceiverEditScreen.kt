package com.ris.imagedistributor.ui.receivers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ris.imagedistributor.data.local.DEFAULT_MAX_COUNT
import com.ris.imagedistributor.data.local.DEFAULT_MIN_COUNT
import com.ris.imagedistributor.data.local.Receiver
import com.ris.imagedistributor.data.local.ReceiverChannel
import com.ris.imagedistributor.data.local.ReceiverWithSchedules
import com.ris.imagedistributor.data.local.channelOrDefault
import com.ris.imagedistributor.ui.components.ScheduleTimeListEditor
import com.ris.imagedistributor.ui.components.addScheduleTime
import com.ris.imagedistributor.ui.theme.GoldBorder

internal const val MIN_SCHEDULE_TIMES = 4

/**
 * DESIGN.md/EXPERIENCE.md — channel is a segmented control that swaps the contact field below
 * it; phone and email fields are never both visible. Save only navigates back (onDone) once the
 * repository call actually succeeds — never before. [Story 1.2 lesson]
 *
 * `isNew` is decided once from [receiverId] (the route's own truth), not from whether [existing]
 * resolved to a value — [Review][Patch] fix: deriving it from `existing == null` meant an
 * edit-in-progress whose data hadn't loaded yet (or was deleted concurrently) silently fell back
 * to "add new" and inserted a duplicate on Save instead of failing or updating.
 *
 * A receiver's schedule is optional — zero times is valid and falls back to the app-wide master
 * schedule at dispatch time (Settings); if any are given, the minimum of 4 still applies —
 * [Sprint Change Proposal 2026-07-12].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiverEditScreen(
    viewModel: ReceiversViewModel,
    receiverId: Long?,
    existing: ReceiverWithSchedules?,
    stillLoading: Boolean,
    onDone: () -> Unit,
) {
    val isNew = receiverId == null

    if (stillLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (!isNew && existing == null) {
        // Receiver was deleted (or never existed) — nothing to edit, bail back to the list
        // rather than silently falling through to an "add new" form.
        LaunchedEffect(Unit) { onDone() }
        return
    }

    val existingReceiver = existing?.receiver
    // [Review][Patch] both fields derive from the same safely-parsed channel now — previously
    // phoneDigits/email checked the raw channel string independently of channelOrDefault(), so a
    // receiver with a corrupted channel value would show the wrong contact field blank, and
    // saving would silently overwrite the real phoneOrEmail with a near-empty value.
    val existingChannel = existingReceiver?.channelOrDefault()
    var name by remember { mutableStateOf(existingReceiver?.name ?: "") }
    var channel by remember { mutableStateOf(existingChannel ?: ReceiverChannel.WHATSAPP) }
    var phoneDigits by remember {
        mutableStateOf(
            existingReceiver?.takeIf { existingChannel == ReceiverChannel.WHATSAPP }?.phoneOrEmail?.removePrefix("+91") ?: ""
        )
    }
    var email by remember {
        mutableStateOf(existingReceiver?.takeIf { existingChannel == ReceiverChannel.EMAIL }?.phoneOrEmail ?: "")
    }
    val scheduleTimes = remember { mutableStateListOf<Int>().apply { addAll(existing?.scheduleTimes.orEmpty()) } }
    var showTimePicker by remember { mutableStateOf(false) }
    var minCountText by remember { mutableStateOf((existingReceiver?.minCount ?: DEFAULT_MIN_COUNT).toString()) }
    var maxCountText by remember { mutableStateOf((existingReceiver?.maxCount ?: DEFAULT_MAX_COUNT).toString()) }

    // [Review][Patch] per-field errors, rendered under each field — EXPERIENCE.md#Component
    // Patterns specifies inline error text under the field, not one shared message.
    var nameError by remember { mutableStateOf<String?>(null) }
    var contactError by remember { mutableStateOf<String?>(null) }
    var scheduleError by remember { mutableStateOf<String?>(null) }
    var countError by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    // [Review][Patch] picking a time already in the list used to silently no-op — this surfaces
    // that so the user knows their tap did nothing rather than assuming it was added.
    var duplicateTimeMessage by remember { mutableStateOf(false) }

    val isSaving by viewModel.isSaving.collectAsState()

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(text = if (isNew) "Add receiver" else "Edit receiver", style = MaterialTheme.typography.headlineSmall)

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            border = BorderStroke(1.5.dp, GoldBorder),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        modifier = Modifier.fillMaxWidth(),
                        isError = nameError != null,
                    )
                    nameError?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }
                }

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ReceiverChannel.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = channel == option,
                            onClick = { channel = option },
                            shape = SegmentedButtonDefaults.itemShape(index, ReceiverChannel.entries.size),
                        ) {
                            Text(if (option == ReceiverChannel.WHATSAPP) "WhatsApp" else "Email")
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (channel == ReceiverChannel.WHATSAPP) {
                        OutlinedTextField(
                            value = phoneDigits,
                            // 10-digit Indian mobile number, per the fixed +91 prefix this form uses.
                            onValueChange = { phoneDigits = it.filter(Char::isDigit).take(10) },
                            label = { Text("Phone (+91)") },
                            modifier = Modifier.fillMaxWidth(),
                            isError = contactError != null,
                        )
                    } else {
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("Email") },
                            modifier = Modifier.fillMaxWidth(),
                            isError = contactError != null,
                        )
                    }
                    contactError?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = minCountText,
                            onValueChange = { minCountText = it.filter(Char::isDigit) },
                            label = { Text("Min images") },
                            modifier = Modifier.weight(1f),
                            isError = countError != null,
                        )
                        OutlinedTextField(
                            value = maxCountText,
                            onValueChange = { maxCountText = it.filter(Char::isDigit) },
                            label = { Text("Max images") },
                            modifier = Modifier.weight(1f),
                            isError = countError != null,
                        )
                    }
                    countError?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }
                }

                ScheduleTimeListEditor(
                    scheduleTimes = scheduleTimes,
                    onAddTimeClick = { showTimePicker = true },
                    duplicateTimeMessage = duplicateTimeMessage,
                    error = scheduleError,
                )

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving,
                    onClick = {
                        val trimmedName = name.trim()
                        val trimmedEmail = email.trim()
                        val contact = if (channel == ReceiverChannel.WHATSAPP) "+91$phoneDigits" else trimmedEmail

                        nameError = if (trimmedName.isBlank()) "Enter a name." else null
                        contactError = when {
                            channel == ReceiverChannel.WHATSAPP && phoneDigits.isBlank() -> "Enter a phone number."
                            // [Review][Patch] a too-short number previously saved successfully
                            // and would silently fail every WhatsApp send.
                            channel == ReceiverChannel.WHATSAPP && phoneDigits.length != 10 -> "Enter a 10-digit phone number."
                            channel == ReceiverChannel.EMAIL && !trimmedEmail.contains("@") -> "Enter a valid email."
                            else -> null
                        }
                        // Empty is valid (falls back to the master schedule); only a partially
                        // filled list (1-3 times) is rejected. [Sprint Change Proposal 2026-07-12]
                        scheduleError = if (scheduleTimes.isNotEmpty() && scheduleTimes.size < MIN_SCHEDULE_TIMES) {
                            "Add at least $MIN_SCHEDULE_TIMES schedule times."
                        } else {
                            null
                        }
                        val minCount = minCountText.toIntOrNull()
                        val maxCount = maxCountText.toIntOrNull()
                        countError = when {
                            minCount == null || minCount < 1 -> "Enter a minimum of at least 1."
                            maxCount == null || maxCount < minCount -> "Max must be at least the minimum."
                            else -> null
                        }
                        if (nameError != null || contactError != null || scheduleError != null || countError != null) {
                            return@Button
                        }

                        saveError = null
                        val receiver = Receiver(
                            id = existingReceiver?.id ?: 0,
                            name = trimmedName,
                            channel = channel.name,
                            phoneOrEmail = contact,
                            minCount = minCount!!,
                            maxCount = maxCount!!,
                        )
                        // saveWithBudgetCheck lives entirely in viewModelScope (budget check
                        // included, not just the persist call) — [Review][Patch] fix: an earlier
                        // version of this ran the budget check on this screen's own
                        // rememberCoroutineScope, which is cancelled if the operator navigates
                        // away while that suspend call is still in flight, silently dropping the
                        // save. Same reasoning as viewModel.save()'s own doc comment.
                        viewModel.saveWithBudgetCheck(receiver, scheduleTimes.toList(), isNew, existingReceiver?.id) { error ->
                            if (error == null) onDone() else saveError = error
                        }
                    },
                ) {
                    Text("Save")
                }

                saveError?.let { message ->
                    Text(text = message, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
