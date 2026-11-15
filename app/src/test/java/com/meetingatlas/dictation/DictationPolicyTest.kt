package com.meetingatlas.dictation

import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class DictationPolicyTest {
    @Test fun numericPasswordAndPrivateEditorsAreIneligible() {
        assertTrue(DictationPolicy.allowsTextType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertFalse(DictationPolicy.allowsTextType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertFalse(DictationPolicy.allowsTextType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertFalse(DictationPolicy.allowsTextType(InputType.TYPE_CLASS_NUMBER))
        assertFalse(DictationPolicy.allowsTextType(InputType.TYPE_CLASS_PHONE))
        assertFalse(DictationPolicy.allowsIme(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        assertFalse(DictationPolicy.allowsMetadata("Verification code", null))
        assertFalse(DictationPolicy.allowsMetadata(null, "example:id/billing_card_number"))
        assertFalse(DictationPolicy.allowsMetadata("Enter PIN"))
        assertTrue(DictationPolicy.allowsMetadata("Write a message", null))
    }

    @Test fun appRestrictionsAndStyleStayLocal() {
        assertFalse(DictationPolicy.allowsPackage("com.google.android.apps.walletnfcrel", "atlas", emptySet()))
        assertFalse(DictationPolicy.allowsPackage("com.example.keyboard", "atlas", setOf("com.example.keyboard")))
        assertFalse(DictationPolicy.allowsPackage("atlas", "atlas", emptySet()))
        assertEquals(WritingStyle.EMAIL, DictationPolicy.style("com.google.android.gm", null, 0))
        assertEquals(WritingStyle.SEARCH, DictationPolicy.style("com.android.chrome", null, EditorInfo.IME_ACTION_SEARCH))
    }

    @Test fun onlyOneSessionOwnsTheRightToInsert() {
        val gate = SessionFence()
        val first = gate.begin()!!
        assertNull(gate.begin())
        assertTrue(gate.end(first))
        val second = gate.begin()!!
        assertNotEquals(first, second)
        assertFalse(gate.owns(first))
        assertFalse(gate.end(first))
        assertTrue(gate.owns(second))
    }

    @Test fun noSpeechNeverProducesAnUploadableWav() {
        val buffer = PcmUtterance(16_000)
        repeat(30) { buffer.add(ShortArray(320), 320) }
        assertThrows(DictationFailure::class.java) { buffer.finish() }
    }

    @Test fun trimsOuterSilenceButPreservesThePauseInsideTheUtterance() {
        val buffer = PcmUtterance(16_000)
        fun frames(number: Int, sound: Short) = repeat(number) { buffer.add(ShortArray(320) { sound }, 320) }
        frames(50, 0)
        frames(20, 1100)
        frames(40, 0)
        frames(20, 1300)
        frames(50, 0)
        val result = buffer.finish()
        val wav = ByteBuffer.wrap(result.wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(result.wav.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals(16_000, wav.getInt(24))
        assertEquals(32_000, wav.getInt(28))
        assertEquals(result.wav.size - 44, wav.getInt(40))
        assertEquals(2050L, result.durationMs)
        assertEquals(1100, wav.getShort(44 + (16_000 / 5) * 2).toInt())
        assertEquals(0, wav.getShort(44 + 16_000 * 2).toInt())
        assertEquals(1300, wav.getShort(44 + 16_000 * 2 * 15 / 10).toInt())
    }
}
