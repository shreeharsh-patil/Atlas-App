package com.meetingatlas.dictation

import android.accessibilityservice.InputMethod
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import java.security.MessageDigest

internal class TextInsertionManager {
    fun snapshot(node: AccessibilityNodeInfo): EditSnapshot? {
        val value = fieldText(node) ?: return null
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        val left = minOf(start, end)
        val right = maxOf(start, end)
        if (value.splitsCodePoint(left) || value.splitsCodePoint(right)) return null
        return snap(value, left, right).takeIf { it.valid }
    }

    /**
     * The caller must still hold the original focus generation, editor connection generation
     * and session. The editor's input connection commits at its own selection, preserving spans,
     * existing draft and undo history. Do not mutate the whole field via ACTION_SET_TEXT.
     */
    fun commit(method: InputMethod?, node: AccessibilityNodeInfo, anchor: EditSnapshot, text: String): EditSnapshot? {
        if (Build.VERSION.SDK_INT < 33 || method?.currentInputStarted != true) return null
        val editor = method.currentInputEditorInfo ?: return null
        if (editor.packageName != node.packageName?.toString() || !DictationPolicy.allowsTextType(editor.inputType) ||
            !DictationPolicy.allowsIme(editor.imeOptions) || !DictationPolicy.allowsMetadata(editor.hintText, editor.fieldName)) return null
        // getSurroundingText is synchronous and a remote editor can block; use the focused
        // node's exposed selection plus the service's independent IME selection notifications.
        val value = fieldText(node) ?: return null
        val selection = snapshot(node) ?: return null
        if (anchor != selection || value.length != anchor.length) return null
        val expected = value.replaceRange(anchor.start, anchor.end, text)
        val cursor = anchor.start + text.length
        val connection = method.currentInputConnection ?: return null
        connection.commitText(text, 1, null)
        return snap(expected, cursor, cursor)
    }

    private fun fieldText(node: AccessibilityNodeInfo): String? {
        if (node.isShowingHintText) return ""
        val text = node.text ?: return ""
        return if (text.length <= 32_000) text.toString() else null
    }

    private fun snap(text: String, start: Int, end: Int) =
        EditSnapshot(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).toList(), text.length, start, end)

    private fun String.splitsCodePoint(at: Int): Boolean =
        at in 1 until length && this[at - 1].isHighSurrogate() && this[at].isLowSurrogate()
}
