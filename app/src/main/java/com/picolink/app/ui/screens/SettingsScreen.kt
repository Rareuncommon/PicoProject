package com.picolink.app.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.picolink.app.MainViewModel
import com.picolink.app.data.ThemeMode
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.themeMode == mode,
                    onClick = { viewModel.setThemeMode(mode) },
                    label = {
                        Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
                    },
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ToggleRow(
                "Dynamic color (Material You)",
                "Use wallpaper-based colors",
                settings.dynamicColor,
            ) { viewModel.setDynamicColor(it) }
        }
        ToggleRow(
            "Keep screen on",
            "Prevents the display sleeping while PicoLink is open",
            settings.keepScreenOn,
        ) { viewModel.setKeepScreenOn(it) }
        ToggleRow(
            "Haptic feedback",
            "Vibrate on pin toggles",
            settings.hapticFeedback,
        ) { viewModel.setHapticFeedback(it) }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Text("Connection", style = MaterialTheme.typography.titleMedium)
        ToggleRow(
            "Auto-connect on launch",
            "Reconnect to the last device when the app opens",
            settings.autoConnectOnLaunch,
        ) { viewModel.setAutoConnectOnLaunch(it) }
        ToggleRow(
            "Auto-reconnect",
            "Retry with backoff if the link drops unexpectedly",
            settings.autoReconnect,
        ) { viewModel.setAutoReconnect(it) }
        ToggleRow(
            "Auto time sync",
            "Push the phone's clock to the Pico on every connect",
            settings.autoTimeSync,
        ) { viewModel.setAutoTimeSync(it) }

        var poll by remember(settings.statusPollSeconds) {
            mutableFloatStateOf(settings.statusPollSeconds.toFloat())
        }
        Text(
            "Status poll interval: ${poll.roundToInt()}s",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Slider(
            value = poll,
            onValueChange = { poll = it },
            onValueChangeFinished = { viewModel.setStatusPollSeconds(poll.roundToInt()) },
            valueRange = 2f..60f,
            steps = 28,
        )
        Text(
            "How often the dashboard refreshes signal strength, uptime, memory and temperature.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Text("About", style = MaterialTheme.typography.titleMedium)
        Text(
            "PicoLink 1.0.0 — BLE control panel for the Raspberry Pi Pico W.\n" +
                "Pair it with the PicoLink MicroPython firmware (see the pico/ folder " +
                "in the project repository).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        settings.lastDeviceAddress?.let {
            Text(
                "Last device: ${settings.lastDeviceName ?: "(unnamed)"} · $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
