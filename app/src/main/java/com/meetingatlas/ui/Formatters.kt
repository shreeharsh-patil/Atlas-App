package com.meetingatlas.ui

/**
 * Short duration label for meeting lists and headers.
 * Sub-minute recordings must not collapse to "0m", which looked like a failed save.
 */
internal fun formatMeetingDuration(milliseconds: Long): String {
    if (milliseconds <= 0) return "Duration unavailable"
    val totalSeconds = milliseconds / 1_000
    val hours = totalSeconds / 3_600
    val minutes = totalSeconds / 60 % 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "%dh %02dm".format(hours, minutes)
        minutes > 0 -> "%dm".format(minutes)
        else -> "%ds".format(seconds.coerceAtLeast(1))
    }
}

/** Dates are shown as a single "date · duration" meta line on meeting rows. */
internal fun meetingMetaLabel(createdLabel: String, durationMs: Long): String =
    "$createdLabel  ·  ${formatMeetingDuration(durationMs)}"
