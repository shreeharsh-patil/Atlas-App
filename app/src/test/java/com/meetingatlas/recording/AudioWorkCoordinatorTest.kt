package com.meetingatlas.recording

import org.junit.Assert.*
import org.junit.Test

class AudioWorkCoordinatorTest {
    @Test fun simultaneousRecordingAndInferenceStartGrantsExactlyOneOwner() {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val barrier = java.util.concurrent.CyclicBarrier(2)
        try {
            val recording = pool.submit<Boolean> { barrier.await(); AudioWorkCoordinator.startRecording("race") }
            val inference = pool.submit<Boolean> { barrier.await(); AudioWorkCoordinator.startProcessing() }
            assertEquals(1, listOf(recording.get(), inference.get()).count { it })
        } finally { AudioWorkCoordinator.finishRecording("race"); AudioWorkCoordinator.finishProcessing(); pool.shutdownNow() }
    }
    @Test fun captureAndInferenceCannotOwnAudioTogether() {
        assertTrue(AudioWorkCoordinator.startRecording("test"))
        try {
            assertFalse(AudioWorkCoordinator.startRecording("second"))
            assertFalse(AudioWorkCoordinator.startProcessing())
            assertFalse(AudioWorkCoordinator.startRecovery())
            AudioWorkCoordinator.finishRecording("wrong-id")
            assertEquals("test", AudioWorkCoordinator.recording.value)
        } finally { AudioWorkCoordinator.finishRecording("test") }
        assertTrue(AudioWorkCoordinator.startProcessing())
        try { assertFalse(AudioWorkCoordinator.startRecording("test")); assertFalse(AudioWorkCoordinator.startProcessing()) }
        finally { AudioWorkCoordinator.finishProcessing() }
        assertTrue(AudioWorkCoordinator.startRecovery())
        try { assertFalse(AudioWorkCoordinator.startRecording("test")); assertFalse(AudioWorkCoordinator.startProcessing()) }
        finally { AudioWorkCoordinator.finishRecovery() }
    }
    @Test fun invalidRecoveredSegmentDoesNotClaimTimeOrChangeItsIdentityOnRetry() {
        val first = segmentEntry("meeting", 2, "/file", SegmentCheck(999, 2048, false), 12000, true)
        assertEquals(0L, first.durationMs)
        assertEquals("INVALID", first.validationStatus)
        assertEquals(first.id, segmentEntry("meeting", 2, "/file", SegmentCheck(1000, 2048, true), 12000, true).id)
    }
}
