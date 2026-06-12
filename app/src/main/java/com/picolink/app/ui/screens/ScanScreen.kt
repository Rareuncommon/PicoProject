package com.picolink.app.ui.screens

import android.annotation.SuppressLint
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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

@SuppressLint("MissingPermission")
@Composable
fun ScanScreen(viewModel: MainViewModel) {
    val isScanning by viewModel.ble.isScanning.collectAsStateWithLifecycle()
    val results by viewModel.ble.scanResults.collectAsStateWithLifecycle()
    val connectionState by viewModel.ble.connectionState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var nameFilter by remember { mutableStateOf("") }

    val filtered = results.filter {
        nameFilter.isBlank() ||
            (it.name ?: "").contains(nameFilter, ignoreCase = true) ||
            it.address.contains(nameFilter, ignoreCase = true)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        when (connectionState) {
            BleManager.ConnectionState.READY -> {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Connected", style = MaterialTheme.typography.titleMedium)
                        Text(
                            settings.lastDeviceName ?: settings.lastDeviceAddress ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { viewModel.disconnect() }) {
                            Text("Disconnect")
                        }
                    }
                }
            }
            BleManager.ConnectionState.CONNECTING,
            BleManager.ConnectionState.DISCOVERING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Connecting…")
                }
            }
            BleManager.ConnectionState.DISCONNECTED -> {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            if (isScanning) viewModel.ble.stopScan() else viewModel.ble.startScan()
                        },
                        enabled = viewModel.ble.isBluetoothEnabled,
                    ) {
                        Text(if (isScanning) "Stop scan" else "Scan for devices")
                    }
                    if (settings.lastDeviceAddress != null) {
                        OutlinedButton(onClick = { viewModel.connectToLastDevice() }) {
                            Icon(Icons.Filled.History, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(settings.lastDeviceName ?: "Last device")
                        }
                    }
                }
                if (!viewModel.ble.isBluetoothEnabled) {
                    Text(
                        "Bluetooth is off — enable it in system settings.",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        Spacer(Modifier.width(8.dp))
        if (isScanning) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
        }

        OutlinedTextField(
            value = nameFilter,
            onValueChange = { nameFilter = it },
            label = { Text("Filter by name or address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )

        Text(
            "${filtered.size} device(s) · sorted by signal strength",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LazyColumn(Modifier.fillMaxSize()) {
            items(filtered, key = { it.address }) { d ->
                ListItem(
                    leadingContent = {
                        Icon(Icons.Filled.Bluetooth, contentDescription = null)
                    },
                    headlineContent = { Text(d.name ?: "(unnamed)") },
                    supportingContent = { Text(d.address) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.SignalCellular4Bar,
                                contentDescription = null,
                                tint = rssiColor(d.rssi),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("${d.rssi} dBm")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { viewModel.connect(d.address, d.name) },
                    enabled = connectionState == BleManager.ConnectionState.DISCONNECTED,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                ) {
                    Text("Connect")
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun rssiColor(rssi: Int) = when {
    rssi > -60 -> MaterialTheme.colorScheme.primary
    rssi > -80 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}
