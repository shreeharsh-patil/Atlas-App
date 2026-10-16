package com.meetingatlas.recording

import android.content.Context
import android.os.storage.StorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordingFileManager @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val root = File(context.filesDir, "recordings")

    fun segment(meetingId: String, sequence: Int): File {
        val directory = File(root, meetingId)
        check(directory.exists() || directory.mkdirs()) { "Unable to create recording directory" }
        return File(directory, "segment-%04d.m4a".format(sequence))
    }

    fun availableBytes(): Long = try {
        context.getSystemService(StorageManager::class.java).getAllocatableBytes(StorageManager.UUID_DEFAULT)
    } catch (_: Exception) {
        root.usableSpace
    }

    /** Delete only direct audio children belonging to this meeting's managed recording directory. */
    fun deleteMeetingAudio(meetingId: String, files: List<File>): Boolean {
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
        if (deleted) directory.delete()
        return deleted
    }
}
