package com.picolink.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.picolink.app.MainViewModel
import com.picolink.app.ble.BleManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(viewModel: MainViewModel, onGoToScan: () -> Unit) {
    val connectionState by viewModel.ble.connectionState.collectAsStateWithLifecycle()
    val rssi by viewModel.ble.rssi.collectAsStateWithLifecycle()
    val sysInfo by viewModel.sysInfo.collectAsStateWithLifecycle()
    val ledOn by viewModel.ledOn.collectAsStateWithLifecycle()
    val drift by viewModel.clockDriftS.collectAsStateWithLifecycle()
    val lastSync by viewModel.lastTimeSyncMs.collectAsStateWithLifecycle()
    val connected = connectionState == BleManager.ConnectionState.READY

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!connected) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("No Pico connected", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Connect to a Raspberry Pi Pico W running the PicoLink firmware to get started.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Button(onClick = onGoToScan) { Text("Find devices") }
                }
            }
        }

        // --- Device status -------------------------------------------------
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Device status", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { viewModel.refreshAll() }, enabled = connected) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }
                StatusRow("Link", if (connected) "Connected" else connectionState.name.lowercase())
                StatusRow("Signal", rssi?.let { "$it dBm" } ?: "—")
                StatusRow("Firmware", sysInfo?.firmware ?: "—")
                StatusRow("Uptime", sysInfo?.uptimeS?.let { formatUptime(it) } ?: "—")
                StatusRow(
                    "Free memory",
                    sysInfo?.freeMemBytes?.let { "%,d bytes".format(it) } ?: "—",
                )
                StatusRow(
                    "CPU clock",
                    sysInfo?.cpuFreqHz?.let { "${it / 1_000_000} MHz" } ?: "—",
                )
            }
        }

        // --- Temperature ----------------------------------------------------
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Thermostat, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("On-chip temperature", style = MaterialTheme.typography.titleSmall)
                    Text(
                        sysInfo?.tempC?.let { "%.1f °C".format(it) } ?: "—",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
        }

        // --- Onboard LED -----------------------------------------------------
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Lightbulb,
                        contentDescription = null,
                        tint = if (ledOn) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Onboard LED", style = MaterialTheme.typography.titleSmall)
                }
                Switch(
                    checked = ledOn,
                    onCheckedChange = { viewModel.toggleLed() },
                    enabled = connected,
                )
            }
        }

        // --- Clock -----------------------------------------------------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AccessTime, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text("Pico clock", style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.height(8.dp))
                StatusRow(
                    "Pico time",
                    sysInfo?.picoEpoch?.let {
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                            .format(Date(it * 1000))
                    } ?: "not set",
                )
                StatusRow(
                    "Drift vs phone",
                    drift?.let { if (it == 0L) "in sync" else "${it}s" } ?: "—",
                )
                StatusRow(
                    "Last sync",
                    lastSync?.let {
                        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it))
                    } ?: "never this session",
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.syncTime() }, enabled = connected) {
                        Text("Sync now")
                    }
                    OutlinedButton(onClick = { viewModel.readPicoTime() }, enabled = connected) {
                        Text("Read Pico time")
                    }
                }
            }
        }

        // --- Quick info -------------------------------------------------------
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Memory, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(
                    "Tip: schedules and saved programs live on the Pico itself, " +
                        "so they keep running even when the app is disconnected.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatUptime(seconds: Long): String {
    val d = seconds / 86400
    val h = (seconds % 86400) / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return buildString {
        if (d > 0) append("${d}d ")
        if (h > 0 || d > 0) append("${h}h ")
        append("${m}m ${s}s")
    }
}
