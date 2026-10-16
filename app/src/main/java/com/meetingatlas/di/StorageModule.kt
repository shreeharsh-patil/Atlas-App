package com.meetingatlas.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.meetingatlas.data.MeetingDao
import com.meetingatlas.data.AudioSegmentDao
import com.meetingatlas.data.MeetingDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS meeting_analysis (meetingId TEXT NOT NULL, transcript TEXT NOT NULL, summary TEXT NOT NULL, keyPointsJson TEXT NOT NULL, decisionsJson TEXT NOT NULL, actionItemsJson TEXT NOT NULL, mindMapJson TEXT NOT NULL, generatedAt INTEGER NOT NULL, audioDeletedAt INTEGER, PRIMARY KEY(meetingId), FOREIGN KEY(meetingId) REFERENCES meetings(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_meeting_analysis_meetingId ON meeting_analysis(meetingId)")
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS recording_sessions (meetingId TEXT NOT NULL PRIMARY KEY, quality TEXT NOT NULL, state TEXT NOT NULL, sampleRate INTEGER NOT NULL, bitRate INTEGER NOT NULL, inputType TEXT NOT NULL, elapsedMs INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(meetingId) REFERENCES meetings(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE TABLE IF NOT EXISTS recording_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, meetingId TEXT NOT NULL, type TEXT NOT NULL, detail TEXT NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(meetingId) REFERENCES meetings(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_recording_events_meetingId ON recording_events(meetingId)")
            db.execSQL("CREATE TABLE IF NOT EXISTS bookmarks (id TEXT NOT NULL PRIMARY KEY, meetingId TEXT NOT NULL, elapsedMs INTEGER NOT NULL, label TEXT NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(meetingId) REFERENCES meetings(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_meetingId ON bookmarks(meetingId)")
            db.execSQL("CREATE TABLE IF NOT EXISTS playback_positions (meetingId TEXT NOT NULL PRIMARY KEY, positionMs INTEGER NOT NULL, speed REAL NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(meetingId) REFERENCES meetings(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        }
    }


    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `transcript_segments` (`id` TEXT NOT NULL, `meetingId` TEXT NOT NULL, `orderIndex` INTEGER NOT NULL, `text` TEXT NOT NULL, `startMs` INTEGER, `endMs` INTEGER, `precision` TEXT NOT NULL, `revision` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`meetingId`) REFERENCES `meetings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_transcript_segments_meetingId_orderIndex` ON `transcript_segments` (`meetingId`, `orderIndex`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `source_coverage` (`transcriptId` TEXT NOT NULL, `disposition` TEXT NOT NULL, `snapshot` INTEGER NOT NULL, PRIMARY KEY(`transcriptId`), FOREIGN KEY(`transcriptId`) REFERENCES `transcript_segments`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            db.execSQL("CREATE TABLE IF NOT EXISTS `note_topics` (`id` TEXT NOT NULL, `meetingId` TEXT NOT NULL, `topicKey` TEXT NOT NULL, `title` TEXT NOT NULL, `position` INTEGER NOT NULL, `collapsed` INTEGER NOT NULL, `userEdited` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`meetingId`) REFERENCES `meetings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_note_topics_meetingId_topicKey` ON `note_topics` (`meetingId`, `topicKey`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `note_entries` (`id` TEXT NOT NULL, `meetingId` TEXT NOT NULL, `entryKey` TEXT NOT NULL, `topicId` TEXT NOT NULL, `section` TEXT NOT NULL, `text` TEXT NOT NULL, `importance` INTEGER NOT NULL, `owner` TEXT, `deadline` TEXT, `verification` TEXT NOT NULL, `userEdited` INTEGER NOT NULL, `highlighted` INTEGER NOT NULL, `completed` INTEGER NOT NULL, `inMap` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `supersededBy` TEXT, `updatedAt` INTEGER NOT NULL, `displayOrder` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`meetingId`) REFERENCES `meetings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`topicId`) REFERENCES `note_topics`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_note_entries_meetingId_entryKey` ON `note_entries` (`meetingId`, `entryKey`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_entries_topicId` ON `note_entries` (`topicId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `note_revisions` (`id` TEXT NOT NULL, `entryId` TEXT NOT NULL, `number` INTEGER NOT NULL, `text` TEXT NOT NULL, `owner` TEXT, `deadline` TEXT, `origin` TEXT NOT NULL, `applied` INTEGER NOT NULL, `snapshot` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`entryId`) REFERENCES `note_entries`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_note_revisions_entryId_number` ON `note_revisions` (`entryId`, `number`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `note_evidence` (`revisionId` TEXT NOT NULL, `transcriptId` TEXT NOT NULL, `sourceRevision` INTEGER NOT NULL, `quote` TEXT NOT NULL, PRIMARY KEY(`revisionId`, `transcriptId`), FOREIGN KEY(`revisionId`) REFERENCES `note_revisions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`transcriptId`) REFERENCES `transcript_segments`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_evidence_transcriptId` ON `note_evidence` (`transcriptId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `meeting_chapters` (`id` TEXT NOT NULL, `meetingId` TEXT NOT NULL, `topicId` TEXT NOT NULL, `title` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`meetingId`) REFERENCES `meetings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`topicId`) REFERENCES `note_topics`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_meeting_chapters_meetingId` ON `meeting_chapters` (`meetingId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_meeting_chapters_topicId` ON `meeting_chapters` (`topicId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `chapter_sources` (`chapterId` TEXT NOT NULL, `transcriptId` TEXT NOT NULL, PRIMARY KEY(`chapterId`, `transcriptId`), FOREIGN KEY(`chapterId`) REFERENCES `meeting_chapters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`transcriptId`) REFERENCES `transcript_segments`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chapter_sources_transcriptId` ON `chapter_sources` (`transcriptId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `notes_state` (`meetingId` TEXT NOT NULL, `status` TEXT NOT NULL, `template` TEXT NOT NULL, `revision` INTEGER NOT NULL, `expectedChunks` INTEGER NOT NULL, `transcribedChunks` INTEGER NOT NULL, `summarizedOrder` INTEGER NOT NULL, `audioDeletedAt` INTEGER, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`meetingId`), FOREIGN KEY(`meetingId`) REFERENCES `meetings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            db.execSQL("CREATE TABLE IF NOT EXISTS `processing_chunks` (`id` TEXT NOT NULL, `meetingId` TEXT NOT NULL, `position` INTEGER NOT NULL, `sourcePath` TEXT NOT NULL, `sourceSize` INTEGER NOT NULL, `sourceModifiedAt` INTEGER NOT NULL, `offsetMs` INTEGER NOT NULL, `startUs` INTEGER NOT NULL, `endUs` INTEGER NOT NULL, `status` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`meetingId`) REFERENCES `meetings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_processing_chunks_meetingId_position` ON `processing_chunks` (`meetingId`, `position`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `notes_jobs` (`id` TEXT NOT NULL, `meetingId` TEXT NOT NULL, `topicId` TEXT, `status` TEXT NOT NULL, `cursor` INTEGER NOT NULL, `template` TEXT NOT NULL, `textModel` TEXT NOT NULL, `transcriptionModel` TEXT NOT NULL, `error` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`meetingId`) REFERENCES `meetings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_jobs_meetingId` ON `notes_jobs` (`meetingId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `summary_snapshots` (`id` TEXT NOT NULL, `meetingId` TEXT NOT NULL, `revision` INTEGER NOT NULL, `throughOrder` INTEGER NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`meetingId`) REFERENCES `meetings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_summary_snapshots_meetingId_revision` ON `summary_snapshots` (`meetingId`, `revision`)")
        }
    }

    val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): MeetingDatabase =
        Room.databaseBuilder(context, MeetingDatabase::class.java, "meeting-atlas.db")
            .addMigrations(*ALL_MIGRATIONS)
            .build()

    @Provides fun dao(database: MeetingDatabase): MeetingDao = database.meetingDao()
    @Provides fun segments(database: MeetingDatabase): AudioSegmentDao = database.audioSegmentDao()
    @Provides fun analysis(database: MeetingDatabase): com.meetingatlas.data.MeetingAnalysisDao = database.meetingAnalysisDao()
    @Provides fun journal(database: MeetingDatabase): com.meetingatlas.data.RecordingJournalDao = database.recordingJournalDao()
}
