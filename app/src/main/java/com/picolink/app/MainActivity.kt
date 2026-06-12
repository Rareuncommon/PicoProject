package com.picolink.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.picolink.app.ui.AppNav
import com.picolink.app.ui.theme.PicoLinkTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()

            LaunchedEffect(settings.keepScreenOn) {
                if (settings.keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            PicoLinkTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                PermissionGate(
                    onShareLog = { shareLog() },
                    viewModel = viewModel,
                )
            }
        }
    }

    private fun shareLog() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "PicoLink session log")
            putExtra(Intent.EXTRA_TEXT, viewModel.exportLogText())
        }
        startActivity(Intent.createChooser(intent, "Share log"))
    }
}

private fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

@Composable
private fun PermissionGate(onShareLog: () -> Unit, viewModel: MainViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    fun allGranted() = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    var granted by remember { mutableStateOf(allGranted()) }
    var autoConnectAttempted by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted = allGranted() }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(requiredPermissions())
    }

    if (granted) {
        LaunchedEffect(Unit) {
            if (!autoConnectAttempted) {
                autoConnectAttempted = true
                val s = viewModel.settings.value
                if (s.autoConnectOnLaunch && s.lastDeviceAddress != null &&
                    viewModel.ble.isBluetoothEnabled
                ) {
                    viewModel.connectToLastDevice()
                }
            }
        }
        AppNav(viewModel = viewModel, onShareLog = onShareLog)
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Bluetooth permission required",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                "PicoLink needs Bluetooth access to find and talk to your Raspberry Pi Pico W. " +
                    "Nearby-device permission is used only for the BLE link, never for location.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            Button(onClick = { launcher.launch(requiredPermissions()) }) {
                Text("Grant permission")
            }
        }
    }
}
