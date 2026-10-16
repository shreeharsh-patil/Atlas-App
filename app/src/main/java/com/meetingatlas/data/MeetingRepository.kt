package com.meetingatlas.data

import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

class MeetingRepository @Inject constructor(private val dao: MeetingDao) {
    fun observeMeetings(): Flow<List<MeetingEntity>> = dao.observeMeetings()
    suspend fun create(title: String): MeetingEntity {
        val now = System.currentTimeMillis()
        return MeetingEntity(id = UUID.randomUUID().toString(), title = title, createdAt = now).also { dao.upsert(it) }
    }
    suspend fun find(id: String) = dao.findById(id)
    suspend fun discardDraft(id: String) = dao.delete(id)
    suspend fun rename(id: String, title: String) = dao.rename(id, title, System.currentTimeMillis())
}
