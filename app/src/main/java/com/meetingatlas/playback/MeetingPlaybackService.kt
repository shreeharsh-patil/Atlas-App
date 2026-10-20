package com.meetingatlas.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
import com.meetingatlas.MainActivity
import com.meetingatlas.MeetingAtlasApplication
import com.meetingatlas.data.MeetingDatabase
import com.meetingatlas.data.PlaybackPositionEntity
import com.meetingatlas.recording.AudioWorkCoordinator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

@AndroidEntryPoint
class MeetingPlaybackService : MediaSessionService() {
    @Inject lateinit var db: MeetingDatabase
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** One writer per meeting: a later write replaces an older pending one. */
    private val pendingWrites = mutableMapOf<String, Job>()

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying && AudioWorkCoordinator.recording.value != null) pause()
                    if (!isPlaying) savePosition(this@apply)
                }
                @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    val oldId = oldPosition.mediaItem?.mediaMetadata?.extras?.getString(MEETING_ID)
                    val newId = newPosition.mediaItem?.mediaMetadata?.extras?.getString(MEETING_ID)
                    if (oldId != null && oldId != newId) saveSnapshot(oldPosition.mediaItem, oldPosition.positionMs, playbackParameters.speed)
                    savePosition(this@apply)
                }
                override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) { savePosition(this@apply) }
            })
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSession.Builder(this, player)
            // Without a session activity the media notification cannot open the app.
            .setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
                override fun onPlayerCommandRequest(session: MediaSession, controller: MediaSession.ControllerInfo, playerCommand: Int): Int {
                    // The whole "start playing" family must be blocked during a capture:
                    // the microphone would pick the playback up and record the meeting's own audio.
                    val blocked = playerCommand in BLOCKED_WHILE_RECORDING
                    return if (blocked && AudioWorkCoordinator.recording.value != null) {
                        SessionResult.RESULT_ERROR_PERMISSION_DENIED
                    } else {
                        SessionResult.RESULT_SUCCESS
                    }
                }
            }).build()
        scope.launch { AudioWorkCoordinator.recording.collect { if (it != null) player.pause() } }
        scope.launch { while (isActive) { delay(15000); if (player.isPlaying) savePosition(player) } }
        scope.launch { PlaybackInvalidation.invalidations.collect { change ->
            // Any invalidation clears the playlist: the deleted files may belong to the
            // meeting currently loaded, and the message is delivered for every change.
            val owner = player.currentMediaItem?.mediaMetadata?.extras?.getString(MEETING_ID)
            if (owner != null && (owner == change.first || owner != null)) {
                player.stop(); player.clearMediaItems()
            }
        } }
    }

    private fun savePosition(player: Player) {
        saveSnapshot(player.currentMediaItem, player.currentPosition, player.playbackParameters.speed)
    }

    private fun saveSnapshot(item: androidx.media3.common.MediaItem?, positionMs: Long, speed: Float) {
        val extras = item?.mediaMetadata?.extras ?: return
        val meetingId = extras.getString(MEETING_ID) ?: return
        val position = extras.getLong(OFFSET_MS) + positionMs.coerceAtLeast(0)
        // The application scope is multi-threaded, so concurrent writes could land out of
        // order and move the saved position backwards. One writer per meeting fixes that.
        pendingWrites[meetingId]?.cancel()
        pendingWrites[meetingId] = (application as MeetingAtlasApplication).applicationScope.launch {
            runCatching { db.recordingJournalDao().savePlayback(PlaybackPositionEntity(meetingId, position, speed)) }
        }
    }

    /** Writes the position straight to the database so it survives the service teardown. */
    private suspend fun saveSnapshotNow(item: androidx.media3.common.MediaItem?, positionMs: Long, speed: Float) {
        val extras = item?.mediaMetadata?.extras ?: return
        val meetingId = extras.getString(MEETING_ID) ?: return
        val position = extras.getLong(OFFSET_MS) + positionMs.coerceAtLeast(0)
        runCatching { db.recordingJournalDao().savePlayback(PlaybackPositionEntity(meetingId, position, speed)) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        if (session?.player?.playWhenReady != true) stopSelf()
    }
    override fun onDestroy() {
        scope.cancel()
        session?.let {
            val item = it.player.currentMediaItem
            val position = it.player.currentPosition
            val speed = it.player.playbackParameters.speed
            // The final position must be persisted before the player is released, otherwise
            // the user restarts the meeting from the beginning.
            runCatching { runBlocking { saveSnapshotNow(item, position, speed) } }
            it.player.release(); it.release()
        }
        session = null
        super.onDestroy()
    }

    companion object {
        const val MEETING_ID = "meetingId"
        const val OFFSET_MS = "offsetMs"
        private val BLOCKED_WHILE_RECORDING = setOf(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_CHANGE_MEDIA_ITEMS,
        )
    }
}
