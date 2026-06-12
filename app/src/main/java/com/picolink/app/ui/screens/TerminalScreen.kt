package com.picolink.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.picolink.app.MainViewModel
import com.picolink.app.MainViewModel.LogKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TerminalScreen(viewModel: MainViewModel, onShareLog: () -> Unit) {
    val log by viewModel.log.collectAsStateWithLifecycle()
    val history by viewModel.terminalHistory.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var historyCursor by remember { mutableIntStateOf(-1) }
    val listState = rememberLazyListState()
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1)
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Session log", style = MaterialTheme.typography.titleMedium)
            Row {
                IconButton(onClick = onShareLog) {
                    Icon(Icons.Filled.Share, contentDescription = "Share log")
                }
                IconButton(onClick = { viewModel.clearLog() }) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear log")
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    RoundedCornerShape(8.dp),
                )
                .padding(8.dp),
        ) {
            itemsIndexed(log) { _, entry ->
                Row {
                    Text(
                        timeFmt.format(Date(entry.timeMs)),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${prefix(entry.kind)} ${entry.text}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = color(entry.kind),
                    )
                }
            }
        }

        Text(
            "Shorthand: ping · info · temp · time · sync · led on|off · read <pin> · " +
                "write <pin> <0|1> · adc <pin> · run <prog> · reset · or raw JSON",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (history.isNotEmpty()) {
                    historyCursor = if (historyCursor < 0) {
                        history.size - 1
                    } else {
                        (historyCursor - 1).coerceAtLeast(0)
                    }
                    input = history[historyCursor]
                }
            }) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = "Previous command")
            }
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    historyCursor = -1
                },
                placeholder = { Text("Command…") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                viewModel.sendTerminalCommand(input)
                input = ""
                historyCursor = -1
            }) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

private fun prefix(kind: LogKind): String = when (kind) {
    LogKind.TX -> "→"
    LogKind.RX -> "←"
    LogKind.EVENT -> "✦"
    LogKind.INFO -> "ℹ"
    LogKind.ERROR -> "✖"
}

@Composable
private fun color(kind: LogKind): Color = when (kind) {
    LogKind.TX -> MaterialTheme.colorScheme.tertiary
    LogKind.RX -> MaterialTheme.colorScheme.onSurface
    LogKind.EVENT -> MaterialTheme.colorScheme.primary
    LogKind.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    LogKind.ERROR -> MaterialTheme.colorScheme.error
}
