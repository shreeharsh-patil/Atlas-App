package com.meetingatlas.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MeetingDao {
    @Query("SELECT * FROM meetings ORDER BY pinned DESC, createdAt DESC")
    fun observeMeetings(): Flow<List<MeetingEntity>>

    @Query("SELECT * FROM meetings WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): MeetingEntity?

    @Query("SELECT * FROM meetings WHERE recordingStatus IN ('RECORDING', 'PAUSED', 'PREPARING')")
    suspend fun unfinished(): List<MeetingEntity>

    /** Every known meeting id, used to find recording directories that no row owns. */
    @Query("SELECT id FROM meetings")
    suspend fun allIds(): List<String>

    @Upsert
    suspend fun upsert(meeting: MeetingEntity)

    @Query("DELETE FROM meetings WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE meetings SET audioPath = NULL, updatedAt = :updatedAt WHERE id = :id")
    suspend fun clearAudioPath(id: String, updatedAt: Long)

    @Query("UPDATE meetings SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, title: String, updatedAt: Long)

    @Query("UPDATE meetings SET recordingStatus = :status, durationMs = :durationMs, audioPath = :audioPath, endedAt = :endedAt, updatedAt = :endedAt, lastError = :error WHERE id = :id")
    suspend fun finish(id: String, status: RecordingStatus, durationMs: Long, audioPath: String?, endedAt: Long, error: String? = null)

    @Query("UPDATE meetings SET recordingStatus = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setStatus(id: String, status: RecordingStatus, updatedAt: Long)
}

@Dao
interface AudioSegmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(segments: List<AudioSegmentEntity>)

    @Query("UPDATE audio_segments SET validationStatus = 'WRITING' WHERE meetingId = :meetingId AND sequence = :sequence")
    suspend fun markWriting(meetingId: String, sequence: Int)

    @Query("SELECT * FROM audio_segments WHERE meetingId = :meetingId ORDER BY sequence")
    fun observeForMeeting(meetingId: String): Flow<List<AudioSegmentEntity>>

    @Query("SELECT * FROM audio_segments WHERE meetingId = :meetingId ORDER BY sequence")
    suspend fun findForMeeting(meetingId: String): List<AudioSegmentEntity>

    @Query("DELETE FROM audio_segments WHERE meetingId = :meetingId")
    suspend fun deleteForMeeting(meetingId: String)

    /** Remove a single segment row, used when an allocated file never received audio. */
    @Query("DELETE FROM audio_segments WHERE meetingId = :meetingId AND sequence = :sequence")
    suspend fun deleteSegment(meetingId: String, sequence: Int)
}
