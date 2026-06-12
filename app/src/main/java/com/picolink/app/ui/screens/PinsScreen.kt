package com.picolink.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.picolink.app.MainViewModel
import com.picolink.app.ble.BleManager
import com.picolink.app.protocol.Protocol
import com.picolink.app.protocol.Protocol.PinMode
import com.picolink.app.protocol.Protocol.PinState
import kotlin.math.roundToInt

@Composable
fun PinsScreen(viewModel: MainViewModel) {
    val pins by viewModel.pins.collectAsStateWithLifecycle()
    val adc by viewModel.adcReadings.collectAsStateWithLifecycle()
    val connectionState by viewModel.ble.connectionState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connected = connectionState == BleManager.ConnectionState.READY
    var expandedPin by rememberSaveable { mutableIntStateOf(-1) }
    var filter by rememberSaveable { mutableStateOf("all") }

    val visiblePins = Protocol.GP_PINS.filter { pin ->
        val state = pins[pin] ?: PinState(pin)
        when (filter) {
            "configured" -> state.mode != PinMode.UNSET
            "adc" -> pin in Protocol.ADC_PINS
            else -> true
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(filter == "all", { filter = "all" }, label = { Text("All") })
            FilterChip(
                filter == "configured",
                { filter = "configured" },
                label = { Text("Configured") },
            )
            FilterChip(filter == "adc", { filter = "adc" }, label = { Text("ADC") })
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { viewModel.refreshPins() }, enabled = connected) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh pins")
            }
        }

        if (!connected) {
            Text(
                "Connect to a Pico to control pins.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(visiblePins, key = { it }) { pin ->
                PinCard(
                    state = pins[pin] ?: PinState(pin),
                    adcVolts = adc[pin],
                    expanded = expandedPin == pin,
                    enabled = connected,
                    haptics = settings.hapticFeedback,
                    onToggleExpand = { expandedPin = if (expandedPin == pin) -1 else pin },
                    viewModel = viewModel,
                )
            }
        }
    }
}

@Composable
private fun PinCard(
    state: PinState,
    adcVolts: Float?,
    expanded: Boolean,
    enabled: Boolean,
    haptics: Boolean,
    onToggleExpand: () -> Unit,
    viewModel: MainViewModel,
) {
    val haptic = LocalHapticFeedback.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // State indicator dot
                Surface(
                    shape = CircleShape,
                    color = when {
                        state.mode == PinMode.UNSET -> MaterialTheme.colorScheme.surfaceVariant
                        state.mode == PinMode.PWM -> MaterialTheme.colorScheme.tertiary
                        state.value == 1 -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier.size(12.dp),
                ) {}
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("GP${state.pin}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        buildString {
                            append(state.mode.label)
                            if (state.mode == PinMode.PWM) {
                                append(" · ${state.pwmFreq}Hz, ${state.pwmDuty.roundToInt()}%")
                            } else if (state.value != null && state.mode != PinMode.UNSET) {
                                append(" · ${if (state.value == 1) "HIGH" else "LOW"}")
                            }
                            if (state.pin in Protocol.ADC_PINS) append(" · ADC")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.mode == PinMode.OUT) {
                    Switch(
                        checked = state.value == 1,
                        onCheckedChange = {
                            if (haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.writePin(state.pin, if (it) 1 else 0)
                        },
                        enabled = enabled,
                    )
                }
                IconButton(onClick = onToggleExpand) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                    )
                }
            }

            if (expanded) {
                Spacer(Modifier.width(8.dp))
                // Mode selection
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(vertical = 8.dp),
                ) {
                    listOf(PinMode.OUT, PinMode.IN, PinMode.IN_PULLUP, PinMode.IN_PULLDOWN)
                        .forEach { mode ->
                            FilterChip(
                                selected = state.mode == mode,
                                onClick = { viewModel.setPinMode(state.pin, mode) },
                                label = {
                                    Text(
                                        when (mode) {
                                            PinMode.OUT -> "Out"
                                            PinMode.IN -> "In"
                                            PinMode.IN_PULLUP -> "In↑"
                                            PinMode.IN_PULLDOWN -> "In↓"
                                            else -> mode.label
                                        }
                                    )
                                },
                                enabled = enabled,
                            )
                        }
                }

                // Input tools
                if (state.mode in listOf(PinMode.IN, PinMode.IN_PULLUP, PinMode.IN_PULLDOWN)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.readPin(state.pin) },
                            enabled = enabled,
                        ) { Text("Read now") }
                        TextButton(
                            onClick = { viewModel.setWatch(state.pin, !state.watched) },
                            enabled = enabled,
                        ) {
                            Icon(
                                if (state.watched) Icons.Filled.Visibility
                                else Icons.Filled.VisibilityOff,
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (state.watched) "Watching" else "Watch changes")
                        }
                    }
                }

                // PWM controls
                PwmControls(state, enabled, viewModel)

                // ADC
                if (state.pin in Protocol.ADC_PINS) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.readAdc(state.pin) },
                            enabled = enabled,
                        ) { Text("Read ADC") }
                        if (adcVolts != null) {
                            Text(
                                "%.3f V".format(adcVolts),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PwmControls(state: PinState, enabled: Boolean, viewModel: MainViewModel) {
    var duty by remember(state.pin) { mutableFloatStateOf(state.pwmDuty) }
    var freqText by remember(state.pin) { mutableStateOf(state.pwmFreq.toString()) }

    Column(Modifier.padding(top = 8.dp)) {
        Text("PWM", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Duty ${duty.roundToInt()}%", Modifier.width(80.dp))
            Slider(
                value = duty,
                onValueChange = { duty = it },
                onValueChangeFinished = {
                    val freq = freqText.toIntOrNull() ?: 1000
                    viewModel.setPwm(state.pin, freq.coerceIn(8, 100_000), duty)
                },
                valueRange = 0f..100f,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = freqText,
                onValueChange = { freqText = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text("Frequency (Hz)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    val freq = (freqText.toIntOrNull() ?: 1000).coerceIn(8, 100_000)
                    viewModel.setPwm(state.pin, freq, duty)
                },
                enabled = enabled,
            ) { Text("Apply") }
            if (state.mode == PinMode.PWM) {
                TextButton(
                    onClick = { viewModel.stopPwm(state.pin) },
                    enabled = enabled,
                ) { Text("Stop") }
            }
        }
    }
}
