package com.meetingatlas.notes

import com.meetingatlas.data.NoteEntryEntity
import com.meetingatlas.data.NoteTopicEntity
import org.junit.Assert.*
import org.junit.Test

class KnowledgeContextTest {
    private fun topic(index: Int) = NoteTopicEntity("t$index", "m", "subject_$index", "Subject $index", index)
    private fun entry(index: Int, topic: String, text: String) = NoteEntryEntity("e$index", "m", "fact_$index", topic, "DECISION", text, 3, null, null, "SOURCE_LINKED", false, false, false, true, 1, null, index.toLong())

    @Test fun oldRelatedDecisionIsRetrievedEvenAfterHundredsOfNewerTopics() {
        val old = topic(0).copy(topicKey = "postgres_migration", title = "Postgres migration")
        val topics = listOf(old) + (1..300).map(::topic)
        val source = "Revisit the Postgres migration; switch the approved maintenance window."
        val selected = KnowledgeContext.topics(topics, source)
        assertEquals(old.id, selected.first().id)
        val entries = listOf(entry(0, old.id, "Postgres migration maintenance was agreed for Friday.")) + (1..300).map { entry(it, "t$it", "Unrelated subject $it.") }
        val previous = KnowledgeContext.entries(entries, selected, source)
        assertEquals("fact_0", previous.first().key)
        assertTrue(previous.size <= 64)
    }

    @Test fun priorKnowledgeRespectsTextBudgetAndExcludesRevisedDecisions() {
        val topics = listOf(topic(0))
        val entries = (0..99).map { entry(it, "t0", "A".repeat(1800)) } + entry(100, "t0", "Obsolete decision").copy(supersededBy = "e0")
        val selected = KnowledgeContext.entries(entries, topics, "decision")
        assertTrue(selected.sumOf { it.text.length } <= 6000)
        assertFalse(selected.any { it.key == "fact_100" })
    }
}
