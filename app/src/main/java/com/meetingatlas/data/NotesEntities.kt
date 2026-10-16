package com.meetingatlas.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "transcript_segments", foreignKeys = [ForeignKey(entity = MeetingEntity::class, parentColumns = ["id"], childColumns = ["meetingId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["meetingId", "orderIndex"], unique = true)])
data class TranscriptSegmentEntity(@PrimaryKey val id: String, val meetingId: String, val orderIndex: Int, val text: String, val startMs: Long?, val endMs: Long?, val precision: String, val revision: Int = 1)

@Entity(tableName = "source_coverage", foreignKeys = [ForeignKey(entity = TranscriptSegmentEntity::class, parentColumns = ["id"], childColumns = ["transcriptId"], onDelete = ForeignKey.CASCADE)])
data class SourceCoverageEntity(@PrimaryKey val transcriptId: String, val disposition: String, val snapshot: Int)

@Entity(tableName = "note_topics", foreignKeys = [ForeignKey(entity = MeetingEntity::class, parentColumns = ["id"], childColumns = ["meetingId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["meetingId", "topicKey"], unique = true)])
data class NoteTopicEntity(@PrimaryKey val id: String, val meetingId: String, val topicKey: String, val title: String, val position: Int, val collapsed: Boolean = false, val userEdited: Boolean = false)

@Entity(tableName = "note_entries", foreignKeys = [ForeignKey(entity = MeetingEntity::class, parentColumns = ["id"], childColumns = ["meetingId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = NoteTopicEntity::class, parentColumns = ["id"], childColumns = ["topicId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["meetingId", "entryKey"], unique = true), Index("topicId")])
data class NoteEntryEntity(@PrimaryKey val id: String, val meetingId: String, val entryKey: String, val topicId: String, val section: String, val text: String, val importance: Int, val owner: String?, val deadline: String?, val verification: String, val userEdited: Boolean, val highlighted: Boolean, val completed: Boolean, val inMap: Boolean, val revision: Int, val supersededBy: String?, val updatedAt: Long, val displayOrder: Int = 0)

@Entity(tableName = "note_revisions", foreignKeys = [ForeignKey(entity = NoteEntryEntity::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["entryId", "number"], unique = true)])
data class NoteRevisionEntity(@PrimaryKey val id: String, val entryId: String, val number: Int, val text: String, val owner: String?, val deadline: String?, val origin: String, val applied: Boolean, val snapshot: Int, val createdAt: Long)

@Entity(tableName = "note_evidence", primaryKeys = ["revisionId", "transcriptId"], foreignKeys = [ForeignKey(entity = NoteRevisionEntity::class, parentColumns = ["id"], childColumns = ["revisionId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = TranscriptSegmentEntity::class, parentColumns = ["id"], childColumns = ["transcriptId"], onDelete = ForeignKey.CASCADE)], indices = [Index("transcriptId")])
data class NoteEvidenceEntity(val revisionId: String, val transcriptId: String, val sourceRevision: Int, val quote: String)

@Entity(tableName = "meeting_chapters", foreignKeys = [ForeignKey(entity = MeetingEntity::class, parentColumns = ["id"], childColumns = ["meetingId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = NoteTopicEntity::class, parentColumns = ["id"], childColumns = ["topicId"], onDelete = ForeignKey.CASCADE)], indices = [Index("meetingId"), Index("topicId")])
data class MeetingChapterEntity(@PrimaryKey val id: String, val meetingId: String, val topicId: String, val title: String, val startMs: Long, val endMs: Long)

@Entity(tableName = "chapter_sources", primaryKeys = ["chapterId", "transcriptId"], foreignKeys = [ForeignKey(entity = MeetingChapterEntity::class, parentColumns = ["id"], childColumns = ["chapterId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = TranscriptSegmentEntity::class, parentColumns = ["id"], childColumns = ["transcriptId"], onDelete = ForeignKey.CASCADE)], indices = [Index("transcriptId")])
data class ChapterSourceEntity(val chapterId: String, val transcriptId: String)

@Entity(tableName = "notes_state", foreignKeys = [ForeignKey(entity = MeetingEntity::class, parentColumns = ["id"], childColumns = ["meetingId"], onDelete = ForeignKey.CASCADE)])
data class NotesStateEntity(@PrimaryKey val meetingId: String, val status: String, val template: String, val revision: Int, val expectedChunks: Int, val transcribedChunks: Int, val summarizedOrder: Int, val audioDeletedAt: Long?, val updatedAt: Long)

@Entity(tableName = "processing_chunks", foreignKeys = [ForeignKey(entity = MeetingEntity::class, parentColumns = ["id"], childColumns = ["meetingId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["meetingId", "position"], unique = true)])
data class ProcessingChunkEntity(@PrimaryKey val id: String, val meetingId: String, val position: Int, val sourcePath: String, val sourceSize: Long, val sourceModifiedAt: Long, val offsetMs: Long, val startUs: Long, val endUs: Long, val status: String)

@Entity(tableName = "notes_jobs", foreignKeys = [ForeignKey(entity = MeetingEntity::class, parentColumns = ["id"], childColumns = ["meetingId"], onDelete = ForeignKey.CASCADE)], indices = [Index("meetingId")])
data class NotesJobEntity(@PrimaryKey val id: String, val meetingId: String, val topicId: String?, val status: String, val cursor: Int, val template: String, val textModel: String, val transcriptionModel: String, val error: String?, val updatedAt: Long)

@Entity(tableName = "summary_snapshots", foreignKeys = [ForeignKey(entity = MeetingEntity::class, parentColumns = ["id"], childColumns = ["meetingId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["meetingId", "revision"], unique = true)])
data class SummarySnapshotEntity(@PrimaryKey val id: String, val meetingId: String, val revision: Int, val throughOrder: Int, val status: String, val createdAt: Long)

data class NotesDocument(
    @Embedded val state: NotesStateEntity,
    @Relation(parentColumn = "meetingId", entityColumn = "meetingId") val topics: List<NoteTopicEntity>,
    @Relation(parentColumn = "meetingId", entityColumn = "meetingId") val entries: List<NoteEntryEntity>,
    @Relation(parentColumn = "meetingId", entityColumn = "meetingId") val chapters: List<MeetingChapterEntity>,
)
data class SourceEvidence(val sourceId: String, val text: String, val startMs: Long?, val endMs: Long?, val precision: String, val quote: String, val sourceRevision: Int, val currentRevision: Int)

@Dao
interface NotesDao {
    @Upsert suspend fun saveSources(values: List<TranscriptSegmentEntity>)
    @Query("SELECT * FROM transcript_segments WHERE meetingId=:id AND orderIndex>:after ORDER BY orderIndex LIMIT :limit") suspend fun sources(id: String, after: Int = -1, limit: Int = 64): List<TranscriptSegmentEntity>
    @Query("SELECT COUNT(*) FROM transcript_segments WHERE meetingId=:id") suspend fun sourceCount(id: String): Int
    @Upsert suspend fun saveCoverage(values: List<SourceCoverageEntity>)
    @Query("SELECT COUNT(*) FROM source_coverage c JOIN transcript_segments t ON t.id=c.transcriptId WHERE t.meetingId=:id") suspend fun coverageCount(id: String): Int
    @Query("SELECT * FROM transcript_segments WHERE id=:id") suspend fun source(id: String): TranscriptSegmentEntity?
    @Query("SELECT * FROM transcript_segments WHERE meetingId=:id ORDER BY orderIndex LIMIT :limit") fun observeSources(id: String, limit: Int): Flow<List<TranscriptSegmentEntity>>
    @Query("SELECT * FROM transcript_segments WHERE meetingId=:id AND (:query='' OR text LIKE '%'||:query||'%') ORDER BY orderIndex LIMIT :limit") fun searchSources(id: String, query: String, limit: Int): Flow<List<TranscriptSegmentEntity>>
    @Query("SELECT t.* FROM transcript_segments t JOIN chapter_sources c ON c.transcriptId=t.id WHERE c.chapterId=:id ORDER BY t.orderIndex") suspend fun chapterTranscript(id: String): List<TranscriptSegmentEntity>
    @Query("SELECT DISTINCT n.* FROM note_entries n JOIN note_revisions r ON r.entryId=n.id AND r.number=n.revision JOIN note_evidence e ON e.revisionId=r.id JOIN chapter_sources c ON c.transcriptId=e.transcriptId WHERE c.chapterId=:id ORDER BY n.updatedAt") suspend fun chapterNotes(id: String): List<NoteEntryEntity>
    @Query("SELECT DISTINCT t.* FROM transcript_segments t JOIN note_evidence e ON e.transcriptId=t.id JOIN note_revisions r ON r.id=e.revisionId JOIN note_entries n ON n.id=r.entryId AND n.revision=r.number WHERE n.topicId=:topicId AND t.orderIndex>:after ORDER BY t.orderIndex LIMIT 64") suspend fun topicSources(topicId: String, after: Int): List<TranscriptSegmentEntity>
    @Upsert suspend fun saveTopics(values: List<NoteTopicEntity>)
    @Query("SELECT * FROM note_topics WHERE meetingId=:id ORDER BY position") suspend fun topics(id: String): List<NoteTopicEntity>
    @Query("SELECT * FROM note_topics WHERE id=:id") suspend fun topic(id: String): NoteTopicEntity?
    @Query("UPDATE note_topics SET collapsed=:collapsed WHERE id=:id") suspend fun collapseTopic(id: String, collapsed: Boolean)
    @Query("UPDATE note_topics SET title=:title,userEdited=1 WHERE id=:id") suspend fun renameTopic(id: String, title: String)
    @Upsert suspend fun saveEntry(value: NoteEntryEntity)
    @Query("SELECT * FROM note_entries WHERE id=:id") suspend fun entry(id: String): NoteEntryEntity?
    @Query("SELECT * FROM note_entries WHERE meetingId=:id ORDER BY updatedAt DESC") suspend fun entries(id: String): List<NoteEntryEntity>
    @Query("UPDATE note_entries SET supersededBy=:newId WHERE id=:oldId") suspend fun supersede(oldId: String, newId: String)
    @Insert suspend fun revision(value: NoteRevisionEntity)
    @Query("SELECT COALESCE(MAX(number),0)+1 FROM note_revisions WHERE entryId=:id") suspend fun nextEntryRevision(id: String): Int
    @Query("SELECT * FROM note_revisions WHERE entryId=:id ORDER BY number DESC") fun history(id: String): Flow<List<NoteRevisionEntity>>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun evidence(values: List<NoteEvidenceEntity>)
    @Query("SELECT t.id AS sourceId,t.text,t.startMs,t.endMs,t.precision,e.quote,e.sourceRevision,t.revision AS currentRevision FROM note_evidence e JOIN transcript_segments t ON e.transcriptId=t.id JOIN note_revisions r ON r.id=e.revisionId JOIN note_entries n ON n.id=r.entryId AND n.revision=r.number WHERE n.id=:id ORDER BY t.orderIndex") suspend fun evidenceForEntry(id: String): List<SourceEvidence>
    @Upsert suspend fun saveChapters(values: List<MeetingChapterEntity>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun chapterSources(values: List<ChapterSourceEntity>)
    @Upsert suspend fun saveState(value: NotesStateEntity)
    @Query("SELECT * FROM notes_state WHERE meetingId=:id") suspend fun state(id: String): NotesStateEntity?
    @Transaction @Query("SELECT * FROM notes_state WHERE meetingId=:id") fun observeDocument(id: String): Flow<NotesDocument?>
    @Upsert suspend fun saveChunks(values: List<ProcessingChunkEntity>)
    @Query("SELECT * FROM processing_chunks WHERE meetingId=:id AND status!='COMPLETE' ORDER BY position LIMIT 1") suspend fun nextChunk(id: String): ProcessingChunkEntity?
    @Query("UPDATE processing_chunks SET status='COMPLETE' WHERE id=:id") suspend fun completeChunk(id: String)
    @Query("UPDATE processing_chunks SET status='PENDING' WHERE meetingId=:id") suspend fun resetChunks(id: String)
    @Upsert suspend fun saveJob(value: NotesJobEntity)
    @Query("SELECT * FROM notes_jobs WHERE id=:id") suspend fun job(id: String): NotesJobEntity?
    @Query("SELECT * FROM notes_jobs WHERE meetingId=:id ORDER BY updatedAt DESC") fun jobs(id: String): Flow<List<NotesJobEntity>>
    @Insert suspend fun snapshot(value: SummarySnapshotEntity)
    @Query("SELECT * FROM summary_snapshots WHERE meetingId=:id ORDER BY revision DESC LIMIT 100") fun snapshots(id: String): Flow<List<SummarySnapshotEntity>>
}
