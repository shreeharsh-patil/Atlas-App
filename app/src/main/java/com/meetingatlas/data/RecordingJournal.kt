package com.meetingatlas.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "recording_sessions", foreignKeys = [ForeignKey(entity = MeetingEntity::class, parentColumns = ["id"], childColumns = ["meetingId"], onDelete = ForeignKey.CASCADE)])
data class RecordingSessionEntity(
    @PrimaryKey val meetingId: String,
    val quality: String,
    val state: String,
    val sampleRate: Int = 0,
    val bitRate: Int = 48000,
    val inputType: String = "SYSTEM",
    val elapsedMs: Long = 0,
    val updatedAt: Long,
)

/** Contains technical codes only. Never put a title, transcript, bookmark label or audio here. */
@Entity(tableName = "recording_events", foreignKeys = [ForeignKey(entity = MeetingEntity::class, parentColumns = ["id"], childColumns = ["meetingId"], onDelete = ForeignKey.CASCADE)], indices = [Index("meetingId")])
data class RecordingEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val meetingId: String,
    val type: String,
    val detail: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "bookmarks", foreignKeys = [ForeignKey(entity = MeetingEntity::class, parentColumns = ["id"], childColumns = ["meetingId"], onDelete = ForeignKey.CASCADE)], indices = [Index("meetingId")])
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val meetingId: String,
    val elapsedMs: Long,
    val label: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "playback_positions", foreignKeys = [ForeignKey(entity = MeetingEntity::class, parentColumns = ["id"], childColumns = ["meetingId"], onDelete = ForeignKey.CASCADE)])
data class PlaybackPositionEntity(
    @PrimaryKey val meetingId: String,
    val positionMs: Long,
    val speed: Float,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface RecordingJournalDao {
    @Upsert suspend fun saveSession(session: RecordingSessionEntity)
    @Query("SELECT * FROM recording_sessions WHERE meetingId = :id") suspend fun session(id: String): RecordingSessionEntity?
    @Query("SELECT * FROM recording_sessions WHERE meetingId = :id") fun observeSession(id: String): Flow<RecordingSessionEntity?>
    @Insert suspend fun event(event: RecordingEventEntity)
    @Query("SELECT * FROM recording_events WHERE meetingId = :id ORDER BY id DESC LIMIT 100") fun events(id: String): Flow<List<RecordingEventEntity>>
    @Insert suspend fun bookmark(bookmark: BookmarkEntity)
    @Query("SELECT * FROM bookmarks WHERE meetingId = :id ORDER BY elapsedMs, createdAt") fun bookmarks(id: String): Flow<List<BookmarkEntity>>
    @Query("UPDATE bookmarks SET label = :label WHERE id = :id") suspend fun labelBookmark(id: String, label: String)
    @Query("DELETE FROM bookmarks WHERE id = :id") suspend fun deleteBookmark(id: String)
    @Upsert suspend fun savePlayback(position: PlaybackPositionEntity)
    @Query("SELECT * FROM playback_positions WHERE meetingId = :id") suspend fun playback(id: String): PlaybackPositionEntity?
}
