package com.picolink.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.picolink.app.MainViewModel
import com.picolink.app.ble.BleManager
import com.picolink.app.protocol.Protocol.MacroStep

@Composable
fun ProgramsScreen(viewModel: MainViewModel) {
    val programs by viewModel.programs.collectAsStateWithLifecycle()
    val connectionState by viewModel.ble.connectionState.collectAsStateWithLifecycle()
    val connected = connectionState == BleManager.ConnectionState.READY
    var showBuilder by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { if (connected) showBuilder = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New program")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Programs on the Pico", style = MaterialTheme.typography.titleMedium)
                Row {
                    IconButton(onClick = { viewModel.stopProgram() }, enabled = connected) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop running program")
                    }
                    IconButton(onClick = { viewModel.refreshPrograms() }, enabled = connected) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }
            }
            Text(
                "Built-in programs ship with the firmware; custom programs are step " +
                    "sequences you build here and save to the Pico's flash.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            if (!connected) {
                Text(
                    "Connect to a Pico to manage programs.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(programs, key = { it.name }) { prog ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(prog.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        if (prog.builtin) "Built-in"
                                        else "${prog.steps.size} step(s)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.runProgram(prog.name) },
                                    enabled = connected,
                                ) {
                                    Icon(
                                        Icons.Filled.PlayArrow,
                                        contentDescription = "Run",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                if (!prog.builtin) {
                                    IconButton(onClick = { viewModel.deleteMacro(prog.name) }) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = "Delete",
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                            if (!prog.builtin && prog.steps.isNotEmpty()) {
                                Text(
                                    prog.steps.joinToString("  →  ") { it.describe() },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showBuilder) {
        MacroBuilderDialog(
            onDismiss = { showBuilder = false },
            onSave = { name, steps ->
                viewModel.saveMacro(name, steps)
                showBuilder = false
            },
        )
    }
}

@Composable
private fun MacroBuilderDialog(
    onDismiss: () -> Unit,
    onSave: (String, List<MacroStep>) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val steps = remember { mutableListOf<MacroStep>().toMutableStateList() }
    var stepType by remember { mutableStateOf("pin") }
    var pin by remember { mutableStateOf("15") }
    var value by remember { mutableStateOf(1) }
    var freq by remember { mutableStateOf("1000") }
    var duty by remember { mutableStateOf("50") }
    var ms by remember { mutableStateOf("500") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New program") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.filter { c -> c.isLetterOrDigit() || c == '_' }.take(20)
                    },
                    label = { Text("Program name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (steps.isNotEmpty()) {
                    Text("Steps", style = MaterialTheme.typography.labelLarge)
                    steps.forEachIndexed { index, step ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${index + 1}. ${step.describe()}",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            IconButton(onClick = { steps.removeAt(index) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove step")
                            }
                        }
                    }
                }

                Text("Add a step", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(stepType == "pin", { stepType = "pin" }, label = { Text("Pin") })
                    FilterChip(stepType == "pwm", { stepType = "pwm" }, label = { Text("PWM") })
                    FilterChip(
                        stepType == "sleep", { stepType = "sleep" },
                        label = { Text("Wait") },
                    )
                }

                when (stepType) {
                    "pin" -> Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = pin,
                            onValueChange = { pin = it.filter { c -> c.isDigit() }.take(2) },
                            label = { Text("GP") },
                            singleLine = true,
                            modifier = Modifier.width(90.dp),
                        )
                        FilterChip(value == 1, { value = 1 }, label = { Text("HIGH") })
                        FilterChip(value == 0, { value = 0 }, label = { Text("LOW") })
                    }
                    "pwm" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = pin,
                            onValueChange = { pin = it.filter { c -> c.isDigit() }.take(2) },
                            label = { Text("GP") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = freq,
                            onValueChange = { freq = it.filter { c -> c.isDigit() }.take(6) },
                            label = { Text("Hz") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = duty,
                            onValueChange = { duty = it.filter { c -> c.isDigit() }.take(3) },
                            label = { Text("Duty %") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    "sleep" -> OutlinedTextField(
                        value = ms,
                        onValueChange = { ms = it.filter { c -> c.isDigit() }.take(6) },
                        label = { Text("Milliseconds") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val step = when (stepType) {
                            "pin" -> pin.toIntOrNull()
                                ?.let { MacroStep("pin", pin = it, value = value) }
                            "pwm" -> pin.toIntOrNull()?.let {
                                MacroStep(
                                    "pwm",
                                    pin = it,
                                    freq = (freq.toIntOrNull() ?: 1000).coerceIn(8, 100_000),
                                    duty = (duty.toFloatOrNull() ?: 50f).coerceIn(0f, 100f),
                                )
                            }
                            else -> MacroStep(
                                "sleep",
                                ms = (ms.toIntOrNull() ?: 500).coerceIn(1, 600_000),
                            )
                        }
                        if (step != null && steps.size < 32) steps.add(step)
                    }) { Text("Add step") }
                    AssistChip(
                        onClick = { steps.clear() },
                        label = { Text("Clear all") },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank() && steps.isNotEmpty()) onSave(name, steps.toList())
                },
            ) { Text("Save to Pico") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
