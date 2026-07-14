package com.ris.imagedistributor.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Shared "Schedule times" list editor — used verbatim by both `ReceiverEditScreen` (a receiver's
 * own, optional schedule) and Settings' master-schedule section (always required, minimum 4).
 * The minimum-count validation itself is the caller's responsibility (passed in as [error]),
 * since the two call sites use different predicates — a receiver's own schedule allows zero, the
 * master schedule never does. [DESIGN.md#Components, EXPERIENCE.md#Component Patterns — Schedule
 * time list]
 */
@Composable
fun ScheduleTimeListEditor(
    scheduleTimes: SnapshotStateList<Int>,
    onAddTimeClick: () -> Unit,
    duplicateTimeMessage: Boolean,
    error: String?,
    label: String = "Schedule times",
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        // [Review][Patch] remove by index, not by value — value-based removal would remove the
        // wrong row if duplicate times were ever present.
        scheduleTimes.forEachIndexed { index, time ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "%02d:%02d".format(time / 60, time % 60),
                    modifier = Modifier.padding(top = 12.dp),
                )
                TextButton(onClick = { scheduleTimes.removeAt(index) }) {
                    Text("Remove")
                }
            }
        }
        Button(onClick = onAddTimeClick, modifier = Modifier.fillMaxWidth()) {
            Text("Add time")
        }
        if (duplicateTimeMessage) {
            Text(text = "That time is already scheduled.", color = MaterialTheme.colorScheme.error)
        }
        error?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }
    }
}

/**
 * Shared add-time logic for both schedule editors — inserts [newTime] in sorted order unless
 * already present. Returns true if added, false on a duplicate (the caller surfaces its own
 * duplicateTimeMessage state accordingly).
 */
fun addScheduleTime(scheduleTimes: SnapshotStateList<Int>, newTime: Int): Boolean {
    if (newTime in scheduleTimes) return false
    scheduleTimes.add(newTime)
    scheduleTimes.sort()
    return true
}
