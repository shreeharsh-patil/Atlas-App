package com.meetingatlas.recording

fun formatRecordingTime(milliseconds: Long): String {
    val seconds = (milliseconds / 1_000).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(seconds / 3_600, seconds / 60 % 60, seconds % 60)
}
