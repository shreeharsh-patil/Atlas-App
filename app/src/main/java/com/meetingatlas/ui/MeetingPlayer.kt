package com.meetingatlas.ui

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.*
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.meetingatlas.data.AudioSegmentEntity
import com.meetingatlas.data.MeetingEntity
import com.meetingatlas.playback.*
import com.meetingatlas.recording.AudioWorkCoordinator
import com.meetingatlas.recording.formatRecordingTime
import java.io.File
import kotlinx.coroutines.delay

@Composable
fun MeetingPlayer(meeting: MeetingEntity, segments: List<AudioSegmentEntity>, viewModel: HomeViewModel, seekRequest: Long?) {
    val playable = remember(segments) { segments.filter { it.durationMs > 0 && it.validationStatus in setOf("FINALIZED", "VALIDATED", "RECOVERED") } }
    if (playable.isEmpty()) return
    val timeline = remember(playable) { PlaybackTimeline(playable.map { it.durationMs }) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var position by remember(meeting.id) { mutableLongStateOf(0) }
    var speed by remember(meeting.id) { mutableFloatStateOf(1f) }
    var playing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var scrubbing by remember { mutableStateOf(false) }
    val activeRecording by AudioWorkCoordinator.recording.collectAsStateWithLifecycle()
    fun owns(player: Player) = player.currentMediaItem?.mediaMetadata?.extras?.getString(MeetingPlaybackService.MEETING_ID) == meeting.id
    fun seek(value: Long) {
        position = value.coerceIn(0, timeline.totalMs)
        controller?.takeIf { owns(it) }?.let { val target = timeline.locate(position); it.seekTo(target.segment, target.offsetMs) }
    }
    LaunchedEffect(meeting.id) {
        viewModel.playbackPosition(meeting.id)?.let { position = it.positionMs.coerceIn(0, timeline.totalMs); speed = it.speed.coerceIn(0.5f, 2f) }
    }
    LaunchedEffect(seekRequest) { seekRequest?.let { seek(it) } }
    DisposableEffect(context) {
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, MeetingPlaybackService::class.java))).buildAsync()
        var disposed = false
        future.addListener({ if (!disposed) runCatching { future.get() }.onSuccess { controller = it }.onFailure { error = "Audio player could not connect. Reopen this meeting to retry." } }, ContextCompat.getMainExecutor(context))
        onDispose { disposed = true; MediaController.releaseFuture(future) }
    }
    DisposableEffect(controller, meeting.id) {
        val listener = object : Player.Listener {
            override fun onPlayerError(failure: PlaybackException) { error = "This audio could not be played. The original files have been kept."; playing = false }
        }
        controller?.addListener(listener)
        onDispose { controller?.removeListener(listener) }
    }
    LaunchedEffect(controller, meeting.id, scrubbing) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                controller?.let { player ->
                    playing = owns(player) && player.isPlaying
                    if (owns(player) && !scrubbing) { position = timeline.position(player.currentMediaItemIndex, player.currentPosition); speed = player.playbackParameters.speed }
                }
                delay(if (playing) 300 else 800)
            }
        }
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Recording", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("${playable.size} ${if (playable.size == 1) "segment" else "segments"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.height(8.dp))
            Slider(value = position.toFloat().coerceIn(0f, timeline.totalMs.toFloat()), onValueChange = { scrubbing = true; position = it.toLong() }, onValueChangeFinished = { seek(position); scrubbing = false }, valueRange = 0f..timeline.totalMs.toFloat(), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Recording playback position" })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatRecordingTime(position), style = MaterialTheme.typography.labelMedium)
                Text(formatRecordingTime(timeline.totalMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { seek(position - 10000) }) { Icon(Icons.Default.Replay10, "Back 10 seconds") }
                FilledIconButton(enabled = controller != null && activeRecording == null, onClick = {
                    error = null
                    controller?.let { player ->
                        if (owns(player) && player.isPlaying) player.pause() else {
                            if (!owns(player)) {
                                val items = playable.mapIndexed { index, segment -> MediaItem.Builder().setMediaId(segment.id).setUri(Uri.fromFile(File(segment.localPath))).setMediaMetadata(MediaMetadata.Builder().setTitle(meeting.title).setArtist("Meeting Atlas").setExtras(Bundle().apply { putString(MeetingPlaybackService.MEETING_ID, meeting.id); putLong(MeetingPlaybackService.OFFSET_MS, timeline.starts[index]) }).build()).build() }
                                val target = timeline.locate(if (position >= timeline.totalMs) 0 else position)
                                player.setMediaItems(items, target.segment, target.offsetMs)
                            } else if (position >= timeline.totalMs) player.seekTo(0, 0)
                            player.setPlaybackSpeed(speed); player.prepare(); player.play()
                        }
                    }
                }, modifier = Modifier.size(52.dp)) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause playback" else "Play recording") }
                IconButton(onClick = { seek(position + 10000) }) { Icon(Icons.Default.Forward10, "Forward 10 seconds") }
                TextButton(onClick = {
                    val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
                    speed = speeds[(speeds.indexOf(speed) + 1) % speeds.size]
                    controller?.takeIf { owns(it) }?.setPlaybackSpeed(speed)
                }, modifier = Modifier.semantics { contentDescription = "Playback speed, $speed times" }) { Text("${speed}×") }
            }
            if (activeRecording != null) Text("Playback is paused during recording.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}
