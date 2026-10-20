package com.meetingatlas.recording

import android.content.Context
import android.os.storage.StorageManager
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordingFileManager @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val root = File(context.filesDir, "recordings")

    fun segment(meetingId: String, sequence: Int): File {
        require(meetingId.matches(Regex("[A-Za-z0-9-]{1,80}")) && sequence > 0) { "Invalid recording identifier" }
        val directory = File(root, meetingId)
        check(directory.exists() || directory.mkdirs()) { "Unable to create recording directory" }
        return File(directory, "segment-%04d.m4a".format(sequence))
    }

    fun availableBytes(): Long = StatFs(context.filesDir.absolutePath).availableBytes

    fun segments(meetingId: String): List<File> {
        require(meetingId.matches(Regex("[A-Za-z0-9-]{1,80}")))
        return File(root, meetingId).listFiles { file -> file.name.matches(Regex("segment-[0-9]+[.]m4a")) }.orEmpty().sortedBy { it.name.substringAfter("segment-").substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE }
    }

    /** Remove a single segment file. */
    fun deleteSegment(meetingId: String, sequence: Int): Boolean {
        require(meetingId.matches(Regex("[A-Za-z0-9-]{1,80}")) && sequence > 0) { "Invalid recording identifier" }
        val file = File(File(root, meetingId), "segment-%04d.m4a".format(sequence))
        return !file.exists() || file.delete()
    }

    /**
     * Directories under the managed recording root that no meeting row owns any more.
     * A crash between deleting the row and deleting the files leaves these behind and
     * they would otherwise never be cleaned up.
     */
    fun orphanDirectories(knownIds: Set<String>): List<File> =
        root.listFiles { file -> file.isDirectory && file.name !in knownIds }.orEmpty().toList()

    /**
     * Delete only direct audio children belonging to this meeting's managed recording directory.
     *
     * Returns false when anything is left behind, so the caller never records a removal
     * that did not actually happen.
     */
    fun deleteMeetingAudio(meetingId: String, files: List<File>): Boolean {
        require(meetingId.matches(Regex("[A-Za-z0-9-]{1,80}")))
        val directory = File(root, meetingId).canonicalFile
        val expectedDirectory = directory.canonicalPath
        var deleted = true
        files.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }.forEach { file ->
            val canonical = runCatching { file.canonicalFile }.getOrNull()
            if (canonical == null || canonical.parentFile?.canonicalPath != expectedDirectory || !canonical.name.endsWith(".m4a")) {
                deleted = false
            } else if (canonical.exists() && !canonical.delete()) {
                deleted = false
            }
        }
        // Sweep any leftover (temporary or partially written) file so the directory can go too.
        directory.listFiles()?.forEach { leftover -> if (!leftover.delete()) deleted = false }
        if (directory.exists() && !directory.delete()) deleted = false
        return deleted
    }
}
