package com.meetingatlas.playback

import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
import androidx.media3.exoplayer.ExoPlayer
import com.meetingatlas.MeetingAtlasApplication
import com.meetingatlas.data.MeetingDatabase
import com.meetingatlas.data.PlaybackPositionEntity
import com.meetingatlas.recording.AudioWorkCoordinator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

@AndroidEntryPoint
class MeetingPlaybackService : MediaSessionService() {
    @Inject lateinit var db: MeetingDatabase
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
        session = MediaSession.Builder(this, player).setCallback(object : MediaSession.Callback {
            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
            override fun onPlayerCommandRequest(session: MediaSession, controller: MediaSession.ControllerInfo, playerCommand: Int): Int {
                return if (playerCommand == Player.COMMAND_PLAY_PAUSE && AudioWorkCoordinator.recording.value != null) SessionResult.RESULT_ERROR_NOT_SUPPORTED else SessionResult.RESULT_SUCCESS
            }
        }).build()
        scope.launch { AudioWorkCoordinator.recording.collect { if (it != null) player.pause() } }
        scope.launch { while (isActive) { delay(15000); if (player.isPlaying) savePosition(player) } }
        scope.launch { PlaybackInvalidation.state.collect { change ->
            if (change != null && player.currentMediaItem?.mediaMetadata?.extras?.getString(MEETING_ID) == change.first) { player.stop(); player.clearMediaItems() }
        } }
    }

    private fun savePosition(player: Player) {
        saveSnapshot(player.currentMediaItem, player.currentPosition, player.playbackParameters.speed)
    }

    private fun saveSnapshot(item: androidx.media3.common.MediaItem?, positionMs: Long, speed: Float) {
        val extras = item?.mediaMetadata?.extras ?: return
        val meetingId = extras.getString(MEETING_ID) ?: return
        val position = extras.getLong(OFFSET_MS) + positionMs.coerceAtLeast(0)
        (application as MeetingAtlasApplication).applicationScope.launch {
            runCatching { db.recordingJournalDao().savePlayback(PlaybackPositionEntity(meetingId, position, speed)) }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        if (session?.player?.playWhenReady != true) stopSelf()
    }
    override fun onDestroy() {
        scope.cancel()
        session?.let { savePosition(it.player); it.player.release(); it.release() }
        session = null
        super.onDestroy()
    }
    companion object { const val MEETING_ID = "meetingId"; const val OFFSET_MS = "offsetMs" }
}
