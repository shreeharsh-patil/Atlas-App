package com.meetingatlas.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.BackHandler
import com.meetingatlas.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun RecordingDiagnostics(id: String, viewModel: HomeViewModel, onBack: () -> Unit) {
    if (!BuildConfig.DEBUG) return
    BackHandler(onBack = onBack)
    val session by remember(id) { viewModel.observeSession(id) }.collectAsStateWithLifecycle(initialValue = null)
    val segments by remember(id) { viewModel.observeSegments(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val events by remember(id) { viewModel.observeDiagnostics(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.ROOT) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = onBack) { Text("Back") }; Text("Recording diagnostics", style = MaterialTheme.typography.headlineSmall); Text("Development build · technical metadata only", style = MaterialTheme.typography.bodySmall) }
        item { session?.let { Text("${it.state} · ${it.quality}\nAAC · ${it.sampleRate.takeIf { rate -> rate > 0 } ?: "device default"} Hz · ${it.bitRate} bps\n${it.inputType}", style = MaterialTheme.typography.bodyMedium) } }
        items(segments, key = { it.id }) { Text("Segment ${it.sequence} · ${it.validationStatus}\n${it.durationMs} ms · ${it.fileSize} bytes", style = MaterialTheme.typography.bodySmall) }
        item { HorizontalDivider(); Text("State and encoder events", style = MaterialTheme.typography.titleSmall) }
        items(events, key = { it.id }) { event -> Text("${clock.format(Date(event.createdAt))}  ${event.type}\n${event.detail}", style = MaterialTheme.typography.bodySmall) }
    }
}
