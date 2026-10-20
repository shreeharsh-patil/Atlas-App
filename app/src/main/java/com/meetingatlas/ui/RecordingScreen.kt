package com.meetingatlas.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.meetingatlas.BuildConfig
import com.meetingatlas.data.MeetingEntity
import com.meetingatlas.data.RecordingStatus
import com.meetingatlas.recording.*
import com.meetingatlas.ui.theme.AtlasTheme
import com.meetingatlas.ui.theme.LocalAtlasTextStyles
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect

@Composable
internal fun recordingElapsed(state: RecordingState, fallback: Long = 0): Long {
    var elapsed by remember { mutableLongStateOf(fallback) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(state, fallback, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (state is RecordingState.Recording) while (true) {
                elapsed = state.elapsedBeforeStart + SystemClock.elapsedRealtime() - state.startedAtElapsed
                // 250 ms keeps the digits from visibly stuttering on a one-second tick.
                delay(250)
            } else elapsed = if (state is RecordingState.Paused) state.elapsedMs else fallback
        }
    }
    return elapsed
}

@Composable
internal fun ActiveRecordingBar(title: String, state: RecordingState, onOpen: () -> Unit) {
    val elapsed = recordingElapsed(state)
    val live = state !is RecordingState.Paused
    Surface(
        color = if (live) AtlasTheme.colors.liveContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen, onClickLabel = "Open the recording").semantics(mergeDescendants = true) {},
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            if (live) {
                Box(Modifier.size(9.dp).background(AtlasTheme.colors.live, CircleShape))
            } else {
                Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        state is RecordingState.Paused -> "Recording paused"
                        state is RecordingState.Finalizing -> "Saving recording"
                        else -> "Recording in progress"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (live) AtlasTheme.colors.onLiveContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Text(formatRecordingTime(elapsed), style = LocalAtlasTextStyles.current.timerSmall)
        }
    }
}

@Composable
private fun MicrophoneWaveform(recording: Boolean) {
    val owner = LocalLifecycleOwner.current
    val samples = remember { FloatArray(64) }
    val cursor = remember { mutableIntStateOf(0) }
    LaunchedEffect(recording, owner) {
        if (recording) {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                RecorderTelemetry.amplitude.collect { amplitude ->
                    samples[cursor.intValue % samples.size] = amplitude
                    cursor.intValue++
                }
            }
        } else {
            samples.fill(0f)
            cursor.intValue = 0
        }
    }
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(86.dp).clearAndSetSemantics { contentDescription = if (recording) "Live microphone amplitude" else "Microphone inactive" }) {
        val step = size.width / samples.size
        // The cursor is read inside the draw lambda, so the canvas redraws on every sample.
        val offset = cursor.intValue
        for (i in samples.indices) {
            val value = samples[(offset + i) % samples.size]
            val height = (value * size.height * 0.85f).coerceAtLeast(2.dp.toPx())
            val x = (i + 0.5f) * step
            drawLine(color.copy(alpha = if (recording) 0.8f else 0.25f), Offset(x, (size.height - height) / 2), Offset(x, (size.height + height) / 2), 3.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
internal fun RecordingScreenV2(meeting: MeetingEntity?, viewModel: HomeViewModel, onBack: () -> Unit, onPermissionDenied: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val state by RecordingProcessState.state.collectAsStateWithLifecycle()
    val health by RecorderTelemetry.health.collectAsStateWithLifecycle()
    val options by viewModel.recordingOptions.collectAsStateWithLifecycle()
    val bookmarks by remember(meeting?.id) { viewModel.observeBookmarks(meeting?.id.orEmpty()) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var starting by rememberSaveable { mutableStateOf(false) }
    fun command(action: String) {
        runCatching { context.startService(Intent(context, MeetingRecordingService::class.java).setAction(action)) }
            .onFailure { viewModel.reportLocalError("The recording control was not available. Reopen the recording screen and try again.") }
    }
    fun start() {
        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { starting = false; return }
        runCatching { ContextCompat.startForegroundService(context, Intent(context, MeetingRecordingService::class.java).setAction(MeetingRecordingService.ACTION_START).putExtra(MeetingRecordingService.EXTRA_MEETING_ID, meeting?.id)) }
            .onFailure { starting = false; viewModel.reportLocalError("Android could not start recording. Check microphone permission and try again while the app is open.") }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { start() }
    fun requestNotificationsAndStart() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else start()
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestNotificationsAndStart() else { starting = false; viewModel.reportLocalError("Microphone permission is needed to record a meeting."); onPermissionDenied() }
    }
    val currentId = when (val session = state) {
        is RecordingState.Preparing -> session.meetingId
        is RecordingState.Recording -> session.meetingId
        is RecordingState.Paused -> session.meetingId
        is RecordingState.Finalizing -> session.meetingId
        else -> null
    }
    val current = currentId == meeting?.id && meeting != null
    // A session for a different meeting is running: this screen must not offer a second capture.
    val otherActive = currentId != null && !current
    val recording = current && state is RecordingState.Recording
    val paused = current && state is RecordingState.Paused
    val finalizing = current && state is RecordingState.Finalizing
    val finished = meeting?.recordingStatus in setOf(RecordingStatus.SAVED, RecordingStatus.INTERRUPTED, RecordingStatus.FAILED)
    val elapsed = recordingElapsed(if (current) state else RecordingState.Idle, meeting?.durationMs ?: 0)
    LaunchedEffect(state) { if (state !is RecordingState.Idle) starting = false }
    // Never leave the start button disabled forever if Android drops the start request silently.
    LaunchedEffect(starting) { if (starting) { delay(12_000); starting = false } }
    if (showDiagnostics && BuildConfig.DEBUG && meeting != null) { RecordingDiagnostics(meeting.id, viewModel) { showDiagnostics = false }; return }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to meetings") }
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    AtlasEyebrow("Recording")
                    Spacer(Modifier.height(2.dp))
                    Text(
                        meeting?.title ?: "New meeting",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            val statusText = when {
                recording -> "Recording"
                paused -> "Paused"
                finalizing -> "Saving audio"
                meeting?.recordingStatus == RecordingStatus.FAILED -> "Recording failed"
                meeting?.recordingStatus == RecordingStatus.INTERRUPTED -> "Recording interrupted"
                finished -> "Recording saved"
                otherActive -> "Another meeting is recording"
                current || starting -> "Preparing microphone"
                else -> "Ready to record"
            }
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 26.dp),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(26.dp),
                border = BorderStroke(1.dp, AtlasTheme.colors.cardBorder),
                shadowElevation = 8.dp,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Surface(
                        color = when {
                            recording -> AtlasTheme.colors.liveContainer
                            paused -> MaterialTheme.colorScheme.surfaceVariant
                            else -> MaterialTheme.colorScheme.primaryContainer
                        },
                        shape = RoundedCornerShape(percent = 50),
                    ) {
                        Text(
                            statusText,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                            color = when {
                                recording -> AtlasTheme.colors.onLiveContainer
                                paused -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.onPrimaryContainer
                            },
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(formatRecordingTime(elapsed), style = LocalAtlasTextStyles.current.timer)
                    Spacer(Modifier.height(6.dp))
                    MicrophoneWaveform(recording)
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                if (current) health.input else options?.let { "${it.quality.label} · about ${it.quality.megabytesPerHour} MB/hour" } ?: "Preparing recording settings…",
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (current) Text("Continues with the screen locked", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            health.warning?.takeIf { current }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp)) }
            val failure = (state as? RecordingState.Failed)?.takeIf { it.meetingId == meeting?.id }?.message ?: meeting?.lastError
            failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp)) }
            Spacer(Modifier.height(36.dp))
            if (current) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { command(if (paused) MeetingRecordingService.ACTION_RESUME else MeetingRecordingService.ACTION_PAUSE) }, enabled = recording || paused, modifier = Modifier.weight(1f).heightIn(min = 56.dp), shape = RoundedCornerShape(12.dp)) {
                        Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, null); Spacer(Modifier.width(8.dp)); Text(if (paused) "Resume" else "Pause")
                    }
                    Button(onClick = { command(MeetingRecordingService.ACTION_STOP) }, enabled = recording || paused, modifier = Modifier.weight(1f).heightIn(min = 56.dp), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.Stop, null); Spacer(Modifier.width(8.dp)); Text("Stop & save") }
                }
                OutlinedButton(onClick = { command(MeetingRecordingService.ACTION_BOOKMARK) }, enabled = recording || paused, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 52.dp), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.BookmarkBorder, null); Spacer(Modifier.width(8.dp)); Text("Bookmark this moment") }
                health.bookmarkMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }) }
            } else if (finished) {
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(12.dp)) { Text("Open meeting") }
            } else {
                if (otherActive) {
                    Text(
                        "Finish or stop the other recording first. Only one meeting can be captured at a time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                Button(onClick = { starting = true; if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) requestNotificationsAndStart() else microphone.launch(Manifest.permission.RECORD_AUDIO) }, enabled = meeting != null && !starting && !otherActive, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp)); Text(if (starting) "Preparing…" else "Start recording") }
                Text("Let everyone know before recording.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 12.dp))
            }
            if (bookmarks.isNotEmpty()) Text("${bookmarks.size} saved ${if (bookmarks.size == 1) "bookmark" else "bookmarks"}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 20.dp))
            if (BuildConfig.DEBUG && meeting != null) TextButton(onClick = { showDiagnostics = true }, modifier = Modifier.padding(top = 12.dp)) { Text("Recording diagnostics") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
