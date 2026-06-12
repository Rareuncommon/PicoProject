package com.picolink.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.picolink.app.MainViewModel
import com.picolink.app.ble.BleManager
import com.picolink.app.ui.screens.DashboardScreen
import com.picolink.app.ui.screens.PinsScreen
import com.picolink.app.ui.screens.ProgramsScreen
import com.picolink.app.ui.screens.ScanScreen
import com.picolink.app.ui.screens.SchedulesScreen
import com.picolink.app.ui.screens.SettingsScreen
import com.picolink.app.ui.screens.TerminalScreen

sealed class Dest(val route: String, val label: String, val icon: ImageVector) {
    data object Dashboard : Dest("dashboard", "Home", Icons.Filled.Dashboard)
    data object Pins : Dest("pins", "Pins", Icons.Filled.Memory)
    data object Schedules : Dest("schedules", "Schedules", Icons.Filled.Schedule)
    data object Programs : Dest("programs", "Programs", Icons.Filled.PlayCircle)
    data object Terminal : Dest("terminal", "Terminal", Icons.AutoMirrored.Filled.Send)
    data object Scan : Dest("scan", "Connect", Icons.Filled.Bluetooth)
    data object Settings : Dest("settings", "Settings", Icons.Filled.Settings)
}

private val bottomDestinations =
    listOf(Dest.Dashboard, Dest.Pins, Dest.Schedules, Dest.Programs, Dest.Terminal)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNav(viewModel: MainViewModel, onShareLog: () -> Unit) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val connectionState by viewModel.ble.connectionState.collectAsStateWithLifecycle()
    val device by viewModel.ble.connectedDevice.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.toasts.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    val subtitle = when (connectionState) {
                        BleManager.ConnectionState.READY ->
                            device?.let { d ->
                                viewModel.settings.value.lastDeviceName ?: d.address
                            } ?: "Connected"
                        BleManager.ConnectionState.CONNECTING,
                        BleManager.ConnectionState.DISCOVERING -> "Connecting…"
                        BleManager.ConnectionState.DISCONNECTED -> "Not connected"
                    }
                    Text("PicoLink · $subtitle", style = MaterialTheme.typography.titleMedium)
                },
                actions = {
                    IconButton(onClick = {
                        navController.navigateSingleTop(Dest.Scan.route)
                    }) {
                        Icon(
                            when (connectionState) {
                                BleManager.ConnectionState.READY ->
                                    Icons.Filled.BluetoothConnected
                                BleManager.ConnectionState.DISCONNECTED ->
                                    Icons.Filled.Bluetooth
                                else -> Icons.Filled.BluetoothSearching
                            },
                            contentDescription = "Connect",
                            tint = if (connectionState == BleManager.ConnectionState.READY) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    IconButton(onClick = {
                        navController.navigateSingleTop(Dest.Settings.route)
                    }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                bottomDestinations.forEach { dest ->
                    NavigationBarItem(
                        selected = currentRoute == dest.route,
                        onClick = { navController.navigateSingleTop(dest.route) },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Dest.Dashboard.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Dest.Dashboard.route) {
                DashboardScreen(viewModel) { navController.navigateSingleTop(Dest.Scan.route) }
            }
            composable(Dest.Pins.route) { PinsScreen(viewModel) }
            composable(Dest.Schedules.route) { SchedulesScreen(viewModel) }
            composable(Dest.Programs.route) { ProgramsScreen(viewModel) }
            composable(Dest.Terminal.route) { TerminalScreen(viewModel, onShareLog) }
            composable(Dest.Scan.route) { ScanScreen(viewModel) }
            composable(Dest.Settings.route) { SettingsScreen(viewModel) }
        }
    }
}

private fun androidx.navigation.NavHostController.navigateSingleTop(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
