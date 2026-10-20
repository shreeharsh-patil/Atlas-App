package com.meetingatlas.recording

import org.junit.Assert.*
import org.junit.Test

class AudioWorkCoordinatorTest {
    @Test fun simultaneousRecordingAndInferenceStartGrantsExactlyOneOwner() {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val barrier = java.util.concurrent.CyclicBarrier(2)
        try {
            val recording = pool.submit<Boolean> { barrier.await(); AudioWorkCoordinator.startRecording("race") }
            val inference = pool.submit<String?> { barrier.await(); AudioWorkCoordinator.startProcessing() }
            assertEquals(1, listOf(recording.get(), inference.get() != null).count { it })
            // The winner must release its lock, otherwise it leaks into the next test.
            AudioWorkCoordinator.finishRecording("race")
            inference.get()?.let { AudioWorkCoordinator.finishProcessing(it) }
        } finally { AudioWorkCoordinator.finishRecording("race"); pool.shutdownNow() }
    }

    @Test fun captureAndInferenceCannotOwnAudioTogether() {
        assertTrue(AudioWorkCoordinator.startRecording("test"))
        try {
            assertFalse(AudioWorkCoordinator.startRecording("second"))
            assertNull(AudioWorkCoordinator.startProcessing())
            assertNull(AudioWorkCoordinator.startRecovery())
            AudioWorkCoordinator.finishRecording("wrong-id")
            assertEquals("test", AudioWorkCoordinator.recording.value)
        } finally { AudioWorkCoordinator.finishRecording("test") }
        val processing = AudioWorkCoordinator.startProcessing()
        assertNotNull(processing)
        try {
            assertFalse(AudioWorkCoordinator.startRecording("test"))
            assertNull(AudioWorkCoordinator.startProcessing())
        } finally { AudioWorkCoordinator.finishProcessing(processing) }
        val recovery = AudioWorkCoordinator.startRecovery()
        assertNotNull(recovery)
        try {
            assertFalse(AudioWorkCoordinator.startRecording("test"))
            assertNull(AudioWorkCoordinator.startProcessing())
        } finally { AudioWorkCoordinator.finishRecovery(recovery) }
    }

    @Test fun onlyTheOwnerCanReleaseTheProcessingLock() {
        val owner = AudioWorkCoordinator.startProcessing()
        assertNotNull(owner)
        try {
            // A caller that never acquired the lock must not be able to release it.
            AudioWorkCoordinator.finishProcessing("some-other-owner")
            assertNull(AudioWorkCoordinator.startProcessing())
            AudioWorkCoordinator.finishProcessing(null)
            // A null owner is also a no-op.
            assertNull(AudioWorkCoordinator.startProcessing())
        } finally { AudioWorkCoordinator.finishProcessing(owner) }
        // The lock is free again once the real owner releases it.
        val second = AudioWorkCoordinator.startProcessing()
        assertNotNull(second)
        AudioWorkCoordinator.finishProcessing(second)
    }

    @Test fun onlyTheOwnerCanReleaseTheRecoveryLock() {
        val owner = AudioWorkCoordinator.startRecovery()
        assertNotNull(owner)
        try {
            AudioWorkCoordinator.finishRecovery("some-other-owner")
            assertNull(AudioWorkCoordinator.startRecovery())
        } finally { AudioWorkCoordinator.finishRecovery(owner) }
        val second = AudioWorkCoordinator.startRecovery()
        assertNotNull(second)
        AudioWorkCoordinator.finishRecovery(second)
    }

    @Test fun invalidRecoveredSegmentDoesNotClaimTimeOrChangeItsIdentityOnRetry() {
        val first = segmentEntry("meeting", 2, "/file", SegmentCheck(999, 2048, false), 12000, true)
        assertEquals(0L, first.durationMs)
        assertEquals("INVALID", first.validationStatus)
        assertEquals(first.id, segmentEntry("meeting", 2, "/file", SegmentCheck(1000, 2048, true), 12000, true).id)
    }
}
