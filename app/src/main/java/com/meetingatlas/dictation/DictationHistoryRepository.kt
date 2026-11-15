package com.meetingatlas.dictation

import android.content.Context
import androidx.room.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Entity(tableName = "dictations", indices = [Index("createdAt")])
internal data class DictationEntry(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val text: String?,
    val error: String?,
    val audioKept: Boolean,
    val durationMs: Long,
)

@Dao internal interface DictationDao {
    @Query("SELECT * FROM dictations ORDER BY createdAt DESC LIMIT 1000")
    fun observe(): kotlinx.coroutines.flow.Flow<List<DictationEntry>>
    @Query("SELECT * FROM dictations WHERE id = :id") suspend fun find(id: String): DictationEntry?
    @Query("SELECT id FROM dictations ORDER BY createdAt DESC LIMIT -1 OFFSET 1000") suspend fun overflow(): List<String>
    @Query("SELECT id FROM dictations WHERE createdAt < :cutoff") suspend fun expired(cutoff: Long): List<String>
    @Query("SELECT id FROM dictations WHERE audioKept = 1 ORDER BY createdAt DESC") suspend fun withAudio(): List<String>
    @Query("DELETE FROM dictations WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM dictations") suspend fun clear()
    @Query("UPDATE dictations SET audioKept = 0 WHERE id = :id") suspend fun clearAudio(id: String)
    @Upsert suspend fun put(entry: DictationEntry)
}

@Database(entities = [DictationEntry::class], version = 1, exportSchema = true)
internal abstract class DictationDatabase : RoomDatabase() {
    abstract fun entries(): DictationDao
}

@Singleton
internal class DictationHistoryRepository @Inject constructor(@ApplicationContext context: Context) {
    // No change to the meeting database or its existing migrations.
    private val db by lazy { Room.databaseBuilder(context, DictationDatabase::class.java, "atlas-dictation.db").build() }
    private val dao get() = db.entries()
    private val audioDir = File(context.noBackupFilesDir, "dictations")
    private val lock = Mutex()
    val entries get() = dao.observe()

    suspend fun prune(settings: DictationSettings) = withContext(Dispatchers.IO) {
        lock.withLock { pruneLocked(settings) }
    }

    suspend fun save(
        settings: DictationSettings, text: String?, error: String?, audio: DictationAudio,
    ): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            if (settings.historyDays == 0 || (text == null && !settings.keepAudio)) return@withLock false
            val id = UUID.randomUUID().toString()
            val kept = if (settings.keepAudio) {
                audioDir.mkdirs()
                try { file(id).writeBytes(audio.wav); true }
                catch (_: java.io.IOException) { file(id).delete(); false }
            } else false
            if (text == null && !kept) return@withLock false
            try { dao.put(DictationEntry(id, System.currentTimeMillis(), text, error, kept, audio.durationMs)) }
            catch (failure: Exception) { file(id).delete(); throw failure }
            pruneLocked(settings)
            true
        }
    }

    suspend fun audio(id: String, settings: DictationSettings): DictationAudio? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!settings.keepAudio || settings.historyDays == 0) return@withLock null
            val entry = dao.find(id)?.takeIf { it.audioKept && it.createdAt >= cutoff(settings) }
                ?: return@withLock null
            val file = file(id)
            if (file.length() !in 45..4_320_044) return@withLock null
            DictationAudio(file.readBytes(), entry.durationMs, false)
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        lock.withLock { deleteLocked(id) }
    }

    private suspend fun pruneLocked(settings: DictationSettings) {
        if (settings.historyDays == 0) {
            audioDir.listFiles()?.forEach { it.delete() }
            dao.clear()
            return
        }
        (dao.expired(cutoff(settings)) + dao.overflow()).distinct().forEach { deleteLocked(it) }
        val kept = dao.withAudio()
        (if (settings.keepAudio) kept.drop(20) else kept).forEach { id ->
            file(id).delete()
            dao.clearAudio(id)
        }
        val validFiles = dao.withAudio().map { "$it.wav" }.toSet()
        audioDir.listFiles()?.filter { it.name !in validFiles }?.forEach { it.delete() }
    }

    private suspend fun deleteLocked(id: String) {
        file(id).delete()
        dao.delete(id)
    }

    private fun cutoff(settings: DictationSettings) = System.currentTimeMillis() - settings.historyDays * 86_400_000L
    private fun file(id: String) = File(audioDir, "${UUID.fromString(id)}.wav")
}
