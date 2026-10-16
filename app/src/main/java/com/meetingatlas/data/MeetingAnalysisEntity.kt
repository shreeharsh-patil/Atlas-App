package com.meetingatlas.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.coroutines.flow.Flow
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Durable, offline-readable results produced from a meeting's local recording. */
@Entity(
    tableName = "meeting_analysis",
    foreignKeys = [ForeignKey(entity = MeetingEntity::class, parentColumns = ["id"], childColumns = ["meetingId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("meetingId")],
)
data class MeetingAnalysisEntity(
    @PrimaryKey val meetingId: String,
    val transcript: String,
    val summary: String,
    val keyPointsJson: String,
    val decisionsJson: String,
    val actionItemsJson: String,
    val mindMapJson: String,
    val generatedAt: Long,
    val audioDeletedAt: Long? = null,
)

@Dao
interface MeetingAnalysisDao {
    @Query("SELECT * FROM meeting_analysis WHERE meetingId = :meetingId LIMIT 1")
    fun observe(meetingId: String): Flow<MeetingAnalysisEntity?>

    @Query("SELECT * FROM meeting_analysis WHERE meetingId = :meetingId LIMIT 1")
    suspend fun find(meetingId: String): MeetingAnalysisEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(analysis: MeetingAnalysisEntity)

    @Query("UPDATE meeting_analysis SET audioDeletedAt = :deletedAt WHERE meetingId = :meetingId")
    suspend fun markAudioDeleted(meetingId: String, deletedAt: Long)
}
