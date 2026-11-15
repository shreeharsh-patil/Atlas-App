package com.meetingatlas.dictation

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meetingatlas.MainActivity
import com.meetingatlas.recording.AudioWorkCoordinator
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android microphone/foreground service and Room tests. No production provider calls. */
@RunWith(AndroidJUnit4::class)
class DictationCaptureTest {
    @Test fun visibleActivityStartsRealMicrophoneAndCancelReleasesIt() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val graph = EntryPointAccessors.fromApplication(context, DictationDependencies::class.java)
        val coordinator = graph.coordinator()
        val prefs = graph.preferences()
        val saved = prefs.data.first()
        val lastToken = graph.keys().key(true)
        val automation = instrumentation.uiAutomation
        automation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            graph.keys().saveKey(true, "instrumentation-unused-key")
            prefs.consent(true)
            prefs.update { it.copy(provider = SpeechProvider.OPEN_AI, openAiModel = "test-never-upload") }
            withTimeout(5_000) { coordinator.settings.first { it.consent && it.model == "test-never-upload" } }
            instrumentation.runOnMainSync {
                val id = coordinator.start()
                assertNotNull(id)
                assertNull(coordinator.start())
            }
            val opened = withTimeout(10_000) { coordinator.status.first { it.phase in setOf(DictationPhase.ERROR, DictationPhase.LISTENING) } }
            assertEquals(opened.message, DictationPhase.LISTENING, opened.phase)
            assertTrue(AudioWorkCoordinator.recording.value?.startsWith("dictation:") == true)
            instrumentation.runOnMainSync { coordinator.cancel() }
            withTimeout(10_000) { AudioWorkCoordinator.recording.first { it == null } }
            assertEquals(DictationPhase.CANCELLED, coordinator.status.value.phase)
            assertNull(coordinator.result.value)
        } finally {
            instrumentation.runOnMainSync { coordinator.cancel(); activity.finish() }
            prefs.update { saved }
            prefs.consent(saved.consent)
            if (lastToken == null) graph.keys().deleteKey(true) else graph.keys().saveKey(true, lastToken)
        }
    }

    @Test fun optedInFailedAudioCanBeRecoveredAndDeletionRemovesIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val history = EntryPointAccessors.fromApplication(context, DictationDependencies::class.java).history()
        val buffer = PcmUtterance(16_000)
        repeat(10) { buffer.add(ShortArray(320) { 1000 }, 320) }
        val audio = buffer.finish()
        assertFalse(history.save(DictationSettings(), "not retained", null, audio))
        val settings = DictationSettings(historyDays = 7, keepAudio = true)
        val fixture = "Test failure ${System.nanoTime()}"
        assertTrue(history.save(settings, null, fixture, audio))
        val entry = history.entries.first().first { it.error == fixture }
        assertTrue(entry.audioKept)
        try {
            assertArrayEquals(audio.wav, history.audio(entry.id, settings)!!.wav)
            assertNull(history.audio(entry.id, settings.copy(keepAudio = false)))
        } finally { history.delete(entry.id) }
        assertTrue(history.entries.first().none { it.id == entry.id })
        assertNull(history.audio(entry.id, settings))
    }
}
