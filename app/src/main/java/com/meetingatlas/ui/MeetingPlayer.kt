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
import androidx.compose.ui.semantics.stateDescription
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
import com.meetingatlas.ui.theme.LocalAtlasTextStyles
import java.io.File
import kotlinx.coroutines.delay

/** A one-shot seek request. The token makes repeated seeks to the same millisecond distinct. */
data class PlaybackSeek(val positionMs: Long, val token: Long = System.nanoTime())

/**
 * Segments that the player can actually use. Screens outside the player use it too, so a seek
 * affordance is never shown when there is nothing to play.
 */
internal fun playableSegments(segments: List<AudioSegmentEntity>): List<AudioSegmentEntity> =
    segments.filter { it.durationMs > 0 && it.validationStatus in setOf("FINALIZED", "VALIDATED", "RECOVERED") }

@Composable
fun MeetingPlayer(
    meeting: MeetingEntity,
    segments: List<AudioSegmentEntity>,
    viewModel: HomeViewModel,
    seekRequest: PlaybackSeek?,
    onSeekHandled: () -> Unit = {},
) {
    val playable = remember(segments) { playableSegments(segments) }
    if (playable.isEmpty()) return
    val timeline = remember(playable) { PlaybackTimeline(playable.map { it.durationMs }) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    // Every piece of per-meeting state is keyed by the meeting id: the player is rendered
    // from the same call site for each meeting, so unkeyed state would leak between them.
    var position by remember(meeting.id) { mutableLongStateOf(0) }
    var speed by remember(meeting.id) { mutableFloatStateOf(1f) }
    var playing by remember(meeting.id) { mutableStateOf(false) }
    var error by remember(meeting.id) { mutableStateOf<String?>(null) }
    var scrubbing by remember(meeting.id) { mutableStateOf(false) }
    // A seek requested before the controller connects must be replayed once it exists,
    // otherwise the request is silently dropped and playback never moves.
    var pendingSeek by remember(meeting.id) { mutableStateOf<Long?>(null) }
    val activeRecording by AudioWorkCoordinator.recording.collectAsStateWithLifecycle()
    fun owns(player: Player) = player.currentMediaItem?.mediaMetadata?.extras?.getString(MeetingPlaybackService.MEETING_ID) == meeting.id
    fun seek(value: Long) {
        position = value.coerceIn(0, timeline.totalMs)
        val current = controller
        if (current != null && owns(current)) {
            val target = timeline.locate(position)
            current.seekTo(target.segment, target.offsetMs)
        } else {
            pendingSeek = position
        }
    }
    LaunchedEffect(meeting.id) {
        viewModel.playbackPosition(meeting.id)?.let { position = it.positionMs.coerceIn(0, timeline.totalMs); speed = it.speed.coerceIn(0.5f, 2f) }
    }
    LaunchedEffect(seekRequest) {
        seekRequest?.let { request -> seek(request.positionMs); onSeekHandled() }
    }
    DisposableEffect(context) {
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, MeetingPlaybackService::class.java))).buildAsync()
        var disposed = false
        future.addListener({ if (!disposed) runCatching { future.get() }.onSuccess { controller = it }.onFailure { error = "Audio player could not connect. Reopen this meeting to retry." } }, ContextCompat.getMainExecutor(context))
        onDispose { disposed = true; MediaController.releaseFuture(future) }
    }
    DisposableEffect(controller, meeting.id) {
        // The controller is captured in a local so the listener is always removed from the
        // instance it was added to, even if the controller changed in the meantime.
        val current = controller
        val listener = object : Player.Listener {
            override fun onPlayerError(failure: PlaybackException) { error = "This audio could not be played. The original files have been kept."; playing = false }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying && current?.let { owns(it) } == true }
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) { speed = playbackParameters.speed }
        }
        current?.addListener(listener)
        onDispose { current?.removeListener(listener) }
    }
    // Replay a seek that arrived before the player was ready.
    LaunchedEffect(controller, meeting.id) {
        val target = pendingSeek ?: return@LaunchedEffect
        val current = controller ?: return@LaunchedEffect
        if (owns(current)) {
            val located = timeline.locate(target.coerceIn(0, timeline.totalMs))
            current.seekTo(located.segment, located.offsetMs)
            pendingSeek = null
        }
    }
    LaunchedEffect(controller, meeting.id, scrubbing) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                controller?.let { player ->
                    playing = owns(player) && player.isPlaying
                    if (owns(player) && !scrubbing) {
                        // A null position means the playlist no longer matches the timeline;
                        // the previous position is kept instead of jumping to the start.
                        timeline.position(player.currentMediaItemIndex, player.currentPosition)?.let { position = it }
                        speed = player.playbackParameters.speed
                    }
                }
                delay(if (playing) 300 else 800)
            }
        }
    }
    AtlasCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Recording", style = MaterialTheme.typography.titleMedium)
                Text("${playable.size} ${if (playable.size == 1) "segment" else "segments"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
        Spacer(Modifier.height(10.dp))
        Slider(
            value = position.toFloat().coerceIn(0f, timeline.totalMs.toFloat()),
            onValueChange = { scrubbing = true; position = it.toLong() },
            onValueChangeFinished = { seek(position); scrubbing = false },
            valueRange = 0f..timeline.totalMs.toFloat(),
            // Seeking before the player connects would move the thumb without moving playback.
            enabled = controller != null,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Recording playback position" },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatRecordingTime(position), style = LocalAtlasTextStyles.current.timerSmall)
            Text(formatRecordingTime(timeline.totalMs), style = LocalAtlasTextStyles.current.timerSmall, color = MaterialTheme.colorScheme.secondary)
        }
        Spacer(Modifier.height(6.dp))
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
            }, modifier = Modifier.size(54.dp)) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause playback" else "Play recording") }
            IconButton(onClick = { seek(position + 10000) }) { Icon(Icons.Default.Forward10, "Forward 10 seconds") }
            TextButton(onClick = {
                val index = speeds.indexOfFirst { it == speed }.takeIf { it >= 0 } ?: SPEED_INDEX_ONE
                speed = speeds[(index + 1) % speeds.size]
                controller?.takeIf { owns(it) }?.setPlaybackSpeed(speed)
            }, modifier = Modifier.semantics {
                // The label is already the visible text; only the state needs to be announced.
                stateDescription = speedLabel(speed)
            }) { Text(speedLabel(speed)) }
        }
        if (activeRecording != null) {
            Text("Playback is paused during recording.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

private val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
private const val SPEED_INDEX_ONE = 1

internal fun speedLabel(speed: Float): String =
    if (kotlin.math.abs(speed % 1f) < 0.001f) "${speed.toInt()}×" else "$speed×"
