package com.meetingatlas.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class MeetingConverters {
    @TypeConverter fun toStatus(value: String): RecordingStatus = RecordingStatus.valueOf(value)
    @TypeConverter fun fromStatus(value: RecordingStatus): String = value.name
}

@Database(entities = [MeetingEntity::class, AudioSegmentEntity::class, MeetingAnalysisEntity::class, RecordingSessionEntity::class, RecordingEventEntity::class, BookmarkEntity::class, PlaybackPositionEntity::class, TranscriptSegmentEntity::class, SourceCoverageEntity::class, NoteTopicEntity::class, NoteEntryEntity::class, NoteRevisionEntity::class, NoteEvidenceEntity::class, MeetingChapterEntity::class, ChapterSourceEntity::class, NotesStateEntity::class, ProcessingChunkEntity::class, NotesJobEntity::class, SummarySnapshotEntity::class], version = 4, exportSchema = true)
@TypeConverters(MeetingConverters::class)
abstract class MeetingDatabase : RoomDatabase() {
    abstract fun meetingDao(): MeetingDao
    abstract fun audioSegmentDao(): AudioSegmentDao
    abstract fun meetingAnalysisDao(): MeetingAnalysisDao
    abstract fun recordingJournalDao(): RecordingJournalDao
    abstract fun notesDao(): NotesDao
}
