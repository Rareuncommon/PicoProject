package com.picolink.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.picolink.app.MainViewModel
import com.picolink.app.ble.BleManager
import com.picolink.app.protocol.Protocol
import com.picolink.app.protocol.Protocol.Schedule
import com.picolink.app.protocol.Protocol.ScheduleAction

@Composable
fun SchedulesScreen(viewModel: MainViewModel) {
    val schedules by viewModel.schedules.collectAsStateWithLifecycle()
    val programs by viewModel.programs.collectAsStateWithLifecycle()
    val connectionState by viewModel.ble.connectionState.collectAsStateWithLifecycle()
    val connected = connectionState == BleManager.ConnectionState.READY
    var editing by remember { mutableStateOf<Schedule?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = {
                if (connected) {
                    editing = null
                    showEditor = true
                }
            }) {
                Icon(Icons.Filled.Add, contentDescription = "Add schedule")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Schedules stored on the Pico",
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(onClick = { viewModel.refreshSchedules() }, enabled = connected) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            }
            Text(
                "Schedules fire on the Pico itself using its synced clock — " +
                    "the phone doesn't need to stay connected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            if (!connected) {
                Text(
                    "Connect to a Pico to manage schedules.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (schedules.isEmpty()) {
                Text(
                    "No schedules yet — tap + to create one.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(schedules, key = { it.id }) { s ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "%02d:%02d  ·  %s".format(s.hour, s.minute, s.name),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    s.action.describe(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    daysLabel(s.days),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = s.enabled,
                                onCheckedChange = {
                                    viewModel.setScheduleEnabled(s.id, it)
                                },
                                enabled = connected,
                            )
                            IconButton(onClick = {
                                editing = s
                                showEditor = true
                            }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit")
                            }
                            IconButton(onClick = { viewModel.deleteSchedule(s.id) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditor) {
        ScheduleEditorDialog(
            existing = editing,
            existingIds = schedules.map { it.id },
            programNames = programs.map { it.name },
            onDismiss = { showEditor = false },
            onSave = {
                viewModel.saveSchedule(it)
                showEditor = false
            },
        )
    }
}

private fun daysLabel(days: Set<Int>): String = when {
    days.size == 7 -> "Every day"
    days == setOf(0, 1, 2, 3, 4) -> "Weekdays"
    days == setOf(5, 6) -> "Weekends"
    days.isEmpty() -> "One-shot (next occurrence)"
    else -> days.sorted().joinToString(" ") { Schedule.DAY_LABELS[it] }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleEditorDialog(
    existing: Schedule?,
    existingIds: List<Int>,
    programNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (Schedule) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    val timeState = rememberTimePickerState(
        initialHour = existing?.hour ?: 8,
        initialMinute = existing?.minute ?: 0,
        is24Hour = true,
    )
    var days by remember { mutableStateOf(existing?.days ?: (0..6).toSet()) }
    var actionType by remember { mutableStateOf(existing?.action?.type ?: "pin") }
    var actionPin by remember { mutableStateOf(existing?.action?.pin?.toString() ?: "15") }
    var actionValue by remember { mutableStateOf(existing?.action?.value ?: 1) }
    var actionFreq by remember { mutableStateOf(existing?.action?.freq?.toString() ?: "1000") }
    var actionDuty by remember { mutableStateOf(existing?.action?.duty?.toString() ?: "50") }
    var actionProg by remember {
        mutableStateOf(existing?.action?.prog ?: programNames.firstOrNull() ?: "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New schedule" else "Edit schedule") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(32) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TimePicker(state = timeState)
                }

                Text("Repeat on", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Schedule.DAY_LABELS.forEachIndexed { index, label ->
                        FilterChip(
                            selected = index in days,
                            onClick = {
                                days = if (index in days) days - index else days + index
                            },
                            label = { Text(label.take(2)) },
                        )
                    }
                }

                Text("Action", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        actionType == "pin", { actionType = "pin" },
                        label = { Text("Set pin") },
                    )
                    FilterChip(
                        actionType == "pwm", { actionType = "pwm" },
                        label = { Text("PWM") },
                    )
                    FilterChip(
                        actionType == "prog", { actionType = "prog" },
                        label = { Text("Run program") },
                    )
                }

                when (actionType) {
                    "pin" -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = actionPin,
                                onValueChange = {
                                    actionPin = it.filter { c -> c.isDigit() }.take(2)
                                },
                                label = { Text("GP pin") },
                                singleLine = true,
                                modifier = Modifier.width(110.dp),
                            )
                            FilterChip(
                                actionValue == 1, { actionValue = 1 },
                                label = { Text("HIGH") },
                            )
                            FilterChip(
                                actionValue == 0, { actionValue = 0 },
                                label = { Text("LOW") },
                            )
                        }
                    }
                    "pwm" -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = actionPin,
                                onValueChange = {
                                    actionPin = it.filter { c -> c.isDigit() }.take(2)
                                },
                                label = { Text("GP pin") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = actionFreq,
                                onValueChange = {
                                    actionFreq = it.filter { c -> c.isDigit() }.take(6)
                                },
                                label = { Text("Hz") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = actionDuty,
                                onValueChange = {
                                    actionDuty = it.filter { c -> c.isDigit() }.take(3)
                                },
                                label = { Text("Duty %") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    "prog" -> {
                        if (programNames.isEmpty()) {
                            OutlinedTextField(
                                value = actionProg,
                                onValueChange = { actionProg = it },
                                label = { Text("Program name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    programNames.take(4).forEach { p ->
                                        FilterChip(
                                            actionProg == p, { actionProg = p },
                                            label = { Text(p) },
                                        )
                                    }
                                }
                                if (programNames.size > 4) {
                                    OutlinedTextField(
                                        value = actionProg,
                                        onValueChange = { actionProg = it },
                                        label = { Text("Or type a name") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val pin = actionPin.toIntOrNull()
                    val action = when (actionType) {
                        "pin" -> pin?.let { ScheduleAction("pin", pin = it, value = actionValue) }
                        "pwm" -> pin?.let {
                            ScheduleAction(
                                "pwm",
                                pin = it,
                                freq = (actionFreq.toIntOrNull() ?: 1000).coerceIn(8, 100_000),
                                duty = (actionDuty.toFloatOrNull() ?: 50f).coerceIn(0f, 100f),
                            )
                        }
                        else -> actionProg.takeIf { it.isNotBlank() }
                            ?.let { ScheduleAction("prog", prog = it) }
                    } ?: return@TextButton
                    if (actionType != "prog" && (pin == null || pin !in Protocol.GP_PINS)) {
                        return@TextButton
                    }
                    onSave(
                        Schedule(
                            id = existing?.id ?: ((existingIds.maxOrNull() ?: 0) + 1),
                            name = name.ifBlank { "Schedule" },
                            hour = timeState.hour,
                            minute = timeState.minute,
                            days = days,
                            enabled = existing?.enabled ?: true,
                            action = action,
                        )
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
