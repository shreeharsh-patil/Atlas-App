package com.meetingatlas.notes

import com.meetingatlas.data.NoteEntryEntity
import com.meetingatlas.data.NoteTopicEntity
import java.util.Locale

/** Retrieve bounded prior knowledge by subject, so an old decision can be revisited hours later. */
object KnowledgeContext {
    private val words = Regex("[\\p{L}\\p{N}]{3,}")
    private val stop = setOf("the", "and", "that", "this", "with", "for", "was", "are", "have", "will", "from", "but", "not", "our", "you", "they", "meeting", "discussed")
    private fun tokens(text: String) = words.findAll(text.lowercase(Locale.ROOT)).map { it.value }.filterNot { it in stop }.toSet()
    fun topics(topics: List<NoteTopicEntity>, source: String): List<NoteTopicEntity> {
        val query = tokens(source)
        // Only topics that share at least one meaningful word are relevant. Returning every
        // topic regardless would fill the prompt with unrelated subjects.
        val relevant = topics.filter { candidate ->
            tokens(candidate.title + " " + candidate.topicKey.replace('_', ' ')).any { word -> word in query }
        }
        return relevant.sortedWith(
            compareByDescending<NoteTopicEntity> { tokens(it.title).count { word -> word in query } }
                .thenByDescending { it.position },
        ).take(16)
    }
    fun entries(entries: List<NoteEntryEntity>, topics: List<NoteTopicEntity>, source: String): List<PriorEntry> {
        val query = tokens(source)
        val keys = topics.associate { it.id to it.topicKey }
        var chars = 0
        val ordered = entries.filter { it.topicId in keys && it.supersededBy == null }.sortedWith(
            compareByDescending<NoteEntryEntity> { tokens(it.text).count { word -> word in query } }
                .thenByDescending { it.section in setOf("DECISION", "ACTION", "PROPOSAL") }.thenByDescending { it.updatedAt },
        )
        return ordered.take(64).mapNotNull { entry ->
            // An unknown stored section name must not abort an otherwise valid retry.
            val section = NoteSection.entries.firstOrNull { it.name == entry.section } ?: return@mapNotNull null
            if (chars + entry.text.length > 6000) null else { chars += entry.text.length; PriorEntry(entry.entryKey, requireNotNull(keys[entry.topicId]), section, entry.text, entry.userEdited) }
        }
    }
}
