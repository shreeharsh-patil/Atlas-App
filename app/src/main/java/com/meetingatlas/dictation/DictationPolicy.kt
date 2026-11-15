package com.meetingatlas.dictation

import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.util.Locale

internal enum class WritingStyle(val instruction: String) {
    GENERAL("Preserve the speaker's tone."),
    MESSAGE("Use natural conversational punctuation."),
    EMAIL("Use professional punctuation and readable paragraphs; do not add greetings or signatures."),
    NOTES("Use readable paragraphs when appropriate."),
    CODE("Preserve technical terms, symbols and casing; never turn plain speech into code."),
    SEARCH("Keep the original search terms and omit decorative punctuation.")
}

internal object DictationPolicy {
    private val sensitive = Regex(
        """pass[ _-]?(word|code|phrase)|\bpin\b|\botp\b|\bcvv\b|\bcvc\b|security.?code|verification.?code|credit.?card|debit.?card|card.?number|account.?number|routing.?number|payment|checkout|one.?time.?code|social.?security|private.?key|seed.?phrase|upi.?pin""",
        RegexOption.IGNORE_CASE,
    )
    private val restricted = setOf(
        "android", "com.android.systemui", "com.android.settings", "com.android.phone",
        "com.android.dialer", "com.google.android.dialer", "com.google.android.gms",
        "com.android.vending", "com.google.android.apps.walletnfcrel", "com.google.android.apps.nbu.paisa.user",
        "com.phonepe.app", "net.one97.paytm", "com.paypal.android.p2pmobile", "com.samsung.android.spay",
    )
    private val restrictedTokens = Regex("""(?:^|[._])(bank|banking|wallet|payment|payments|authenticator|passwordmanager)(?:[._]|$)""")
    fun allowsPackage(pkg: String, own: String, excluded: Set<String>): Boolean =
        pkg.isNotBlank() && pkg != own && pkg !in restricted && pkg !in excluded && !restrictedTokens.containsMatchIn(pkg)

    fun allowsTextType(type: Int): Boolean {
        if (type and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return when (type and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> false
            else -> true
        }
    }

    fun allowsMetadata(vararg labels: CharSequence?): Boolean = labels.none {
        it != null && sensitive.containsMatchIn(it)
    }

    fun allowsIme(options: Int): Boolean = options and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0

    fun style(pkg: String, id: String?, action: Int): WritingStyle = when {
        action and EditorInfo.IME_MASK_ACTION == EditorInfo.IME_ACTION_SEARCH -> WritingStyle.SEARCH
        pkg == "com.google.android.gm" || pkg.contains("outlook") || pkg.contains("mail") -> WritingStyle.EMAIL
        pkg in setOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.discord",
            "com.google.android.apps.messaging", "org.thoughtcrime.securesms") -> WritingStyle.MESSAGE
        pkg.contains("code") || pkg.contains("termux") -> WritingStyle.CODE
        pkg.contains("note") || pkg.contains("keep") || id?.endsWith("note_body") == true -> WritingStyle.NOTES
        else -> WritingStyle.GENERAL
    }

    fun triggerKey(text: String): String = text.trim().trimEnd('.', '!', '?', '।')
        .trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
}

/** Owns identity only; stale results cannot be accepted even if focus returns to the same node. */
internal class SessionFence {
    private var nextId = 0L
    private var active: Long? = null

    fun begin(): Long? {
        if (active != null) return null
        return (++nextId).also { active = it }
    }

    fun owns(id: Long): Boolean = active == id

    fun end(id: Long): Boolean {
        if (!owns(id)) return false
        active = null
        return true
    }
}

internal data class EditSnapshot(val fingerprint: List<Byte>, val length: Int, val start: Int, val end: Int) {
    val valid: Boolean get() = start in 0..length && end in start..length
}
