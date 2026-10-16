package com.meetingatlas.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class RecordingStatus { PREPARING, RECORDING, PAUSED, SAVED, INTERRUPTED, FAILED }

@Entity(tableName = "meetings")
data class MeetingEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val durationMs: Long = 0,
    val recordingStatus: RecordingStatus = RecordingStatus.PREPARING,
    val pinned: Boolean = false,
    val updatedAt: Long = createdAt,
    val audioPath: String? = null,
    val lastError: String? = null,
)

@Entity(
    tableName = "audio_segments",
    foreignKeys = [ForeignKey(entity = MeetingEntity::class, parentColumns = ["id"], childColumns = ["meetingId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("meetingId"), Index(value = ["meetingId", "sequence"], unique = true)],
)
data class AudioSegmentEntity(
    @PrimaryKey val id: String,
    val meetingId: String,
    val sequence: Int,
    val localPath: String,
    val startOffsetMs: Long,
    val durationMs: Long,
    val fileSize: Long,
    val validationStatus: String = "FINALIZED",
)
