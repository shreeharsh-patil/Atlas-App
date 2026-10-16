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

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): MeetingDatabase =
        Room.databaseBuilder(context, MeetingDatabase::class.java, "meeting-atlas.db")
            .addMigrations(MIGRATION_1_2)
            .build()

    @Provides fun dao(database: MeetingDatabase): MeetingDao = database.meetingDao()
    @Provides fun segments(database: MeetingDatabase): AudioSegmentDao = database.audioSegmentDao()
    @Provides fun analysis(database: MeetingDatabase): com.meetingatlas.data.MeetingAnalysisDao = database.meetingAnalysisDao()
}
