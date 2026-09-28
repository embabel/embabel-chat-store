/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.chat.store.repository

import com.embabel.chat.MessageRole
import com.embabel.chat.store.TestApplication
import com.embabel.chat.store.model.MessageData
import com.embabel.chat.store.model.TestSessionUser
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.VectorIndexSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import java.util.UUID

/**
 * Re-embedding, against a real database.
 *
 * NOT `@Transactional`, unlike [ChatSessionRepositoryImplTest]: the method under test takes the
 * schema DDL outside any transaction deliberately (see [ChatSessionRepositoryImpl.reembedMessages]),
 * and a test-managed transaction around it would deadlock the thing it is testing.
 *
 * The reason this talks to a database rather than a mocked `PersistenceManager` is the defect that
 * prompted it: the page query projected two COLUMNS where `transform(Class)` deserializes one VALUE
 * per row, so every run died with "Cannot deserialize value of type MessageText from Array value".
 * A mock returns whatever it was told to and sees none of that. The failure reached a running
 * appliance, where it aborted the model change outright and left the store on the old model.
 */
@SpringBootTest(classes = [TestApplication::class])
class ChatSessionReembedIntegrationTest {

    @Autowired
    private lateinit var chatSessionRepository: ChatSessionRepository

    @Autowired
    private lateinit var graphObjectManager: GraphObjectManager

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    private lateinit var sessionId: String

    @BeforeEach
    fun setUp() {
        persistenceManager.execute(QuerySpecification.withStatement("MATCH (m:StoredMessage) DETACH DELETE m"))
        val user = TestSessionUser(UUID.randomUUID().toString(), "Re-embed User")
        graphObjectManager.save(user)
        sessionId = UUID.randomUUID().toString()
        chatSessionRepository.createSession(sessionId, user, "Re-embed")
        listOf("the first thing said", "the second thing said").forEach {
            chatSessionRepository.addMessage(sessionId, MessageData(UUID.randomUUID().toString(), MessageRole.USER, it, Instant.now()))
        }
    }

    @Test
    fun `rewrites every message vector and moves the index to the new width`() {
        chatSessionRepository.reembedMessages("narrow", spec(NARROW), constantVectors(NARROW))

        val widened = chatSessionRepository.reembedMessages("wide", spec(WIDE), constantVectors(WIDE))

        assertEquals(2, widened.messages, "both messages should have been rewritten")
        assertTrue(widened.indexRecreated, "a width change must rebuild the index")
        assertEquals(listOf(WIDE, WIDE), storedWidths(), "stored vectors should be at the new width")
        assertEquals(WIDE, indexDimensions(), "the index should describe the new width")
    }

    @Test
    fun `does no work for messages already at the current model`() {
        chatSessionRepository.reembedMessages("wide", spec(WIDE), constantVectors(WIDE))

        // Would throw if it asked for a vector: nothing is left to rewrite.
        val again = chatSessionRepository.reembedMessages("wide", spec(WIDE)) { error("should not embed") }

        assertEquals(0, again.messages)
    }

    @Test
    fun `a failure mid-reembed restores the index, names the failed message, keeps the rest, and a rerun finishes`() {
        val poisonId = addMessage("$POISON too long for the model")
        chatSessionRepository.reembedMessages("narrow", spec(NARROW), constantVectors(NARROW))

        val failure = assertThrows(MessageReembedIncompleteException::class.java) {
            chatSessionRepository.reembedMessages("wide", spec(WIDE), refusing(WIDE) { POISON in it })
        }

        assertEquals(listOf(poisonId), failure.failedMessageIds)
        assertEquals(2, failure.report.messages, "the other messages should have been written")
        assertTrue(failure.report.indexRecreated)
        assertFalse(failure.abandoned)
        assertEquals(0, failure.notAttempted)
        assertTrue(failure.message.orEmpty().contains(poisonId))
        assertEquals(WIDE, indexDimensions(), "the index must be remade even though the run failed")
        assertEquals(2, countAtModel("wide"))

        val rerun = chatSessionRepository.reembedMessages("wide", spec(WIDE), constantVectors(WIDE))

        assertEquals(1, rerun.messages, "only the message that failed is left to do")
        assertEquals(3, countAtModel("wide"))
    }

    @Test
    fun `one oversized message fails only itself`() {
        repeat(8) { addMessage("filler $it") }
        val poisonId = addMessage("$POISON oversized")
        repeat(8) { addMessage("more filler $it") }
        val calls = mutableListOf<Int>()

        val failure = assertThrows(MessageReembedIncompleteException::class.java) {
            chatSessionRepository.reembedMessages("wide", spec(WIDE)) { texts ->
                calls += texts.size
                refusing(WIDE) { POISON in it }(texts)
            }
        }

        assertEquals(listOf(poisonId), failure.failedMessageIds)
        assertEquals(18, failure.report.messages)
        assertEquals(18, countAtModel("wide"))
        assertTrue(calls.contains(1), "the failing batch should have been split down to the single message")
    }

    @Test
    fun `a dead embedding service stops within the abandon limit and still restores the index`() {
        repeat(28) { addMessage("message $it") }
        chatSessionRepository.reembedMessages("narrow", spec(NARROW), constantVectors(NARROW))
        var calls = 0

        val failure = assertThrows(MessageReembedIncompleteException::class.java) {
            chatSessionRepository.reembedMessages("wide", spec(WIDE)) {
                calls++
                throw IllegalStateException("service down")
            }
        }

        // ceil(log2(200)) + 3
        assertEquals(11, calls, "should give up after the abandon limit, not probe every message")
        assertTrue(failure.abandoned)
        assertEquals(0, failure.report.messages)
        assertEquals(30, failure.failedMessageIds.size + failure.notAttempted, "every message is accounted for")
        assertEquals("service down", failure.cause?.message)
        assertEquals(WIDE, indexDimensions())
    }

    @Test
    fun `a vector count that does not match the texts is a failed call, not a partial write`() {
        val calls = mutableListOf<Int>()

        // Answers with one extra vector for any batch of more than one text.
        val report = chatSessionRepository.reembedMessages("wide", spec(WIDE)) { texts ->
            calls += texts.size
            List(texts.size + if (texts.size > 1) 1 else 0) { List(WIDE) { 0.1 } }
        }

        assertEquals(2, report.messages)
        assertEquals(listOf(2, 1, 1), calls, "the mismatched call should be split and retried")

        val failure = assertThrows(MessageReembedIncompleteException::class.java) {
            chatSessionRepository.reembedMessages("wider", spec(WIDE)) { texts -> List(texts.size + 1) { List(WIDE) { 0.1 } } }
        }
        assertEquals(2, failure.failedMessageIds.size)
        assertTrue(failure.cause is IllegalStateException)
    }

    @Test
    fun `a message saved without a vector after a failed embed is picked up by the next re-embed`() {
        chatSessionRepository.reembedMessages("wide", spec(WIDE), constantVectors(WIDE))
        // As StoredConversation saves a message whose embedding failed: no vector, no model.
        val pendingId = addMessage("embedding failed on save")

        val embedded = mutableListOf<String>()
        val report = chatSessionRepository.reembedMessages("wide", spec(WIDE)) { texts ->
            embedded += texts
            constantVectors(WIDE)(texts)
        }

        assertEquals(1, report.messages)
        assertEquals(listOf("embedding failed on save"), embedded)
        assertEquals(3, countAtModel("wide"))
        assertTrue(pendingId.isNotEmpty())
    }

    private fun addMessage(content: String): String {
        val id = UUID.randomUUID().toString()
        chatSessionRepository.addMessage(sessionId, MessageData(id, MessageRole.USER, content, Instant.now()))
        return id
    }

    /** Vectors for every batch, except that a batch holding a text matching [refuse] fails. */
    private fun refusing(dimensions: Int, refuse: (String) -> Boolean): (List<String>) -> List<List<Double>> =
        { texts ->
            if (texts.any(refuse)) throw IllegalArgumentException("input too long")
            texts.map { List(dimensions) { 0.1 } }
        }

    private fun countAtModel(model: String): Int = persistenceManager.getOne(
        QuerySpecification
            .withStatement("MATCH (m:StoredMessage) WHERE m.embeddingModel = \$model RETURN count(m)")
            .bind(mapOf("model" to model))
            .transform(Long::class.java),
    ).toInt()

    private fun spec(dimensions: Int) = VectorIndexSpec(
        label = "StoredMessage",
        property = "embedding",
        dimensions = dimensions,
        similarity = SimilarityFunction.COSINE,
        name = "StoredMessage_embedding_vector",
    )

    private fun constantVectors(dimensions: Int): (List<String>) -> List<List<Double>> =
        { texts -> texts.map { List(dimensions) { 0.1 } } }

    private fun storedWidths(): List<Int> = persistenceManager.query(
        QuerySpecification
            .withStatement("MATCH (m:StoredMessage) WHERE m.embedding IS NOT NULL RETURN size(m.embedding)")
            .transform(Long::class.java),
    ).map { it.toInt() }

    private fun indexDimensions(): Int = persistenceManager.query(
        QuerySpecification
            .withStatement(
                """
                SHOW INDEXES YIELD name, options
                WHERE name = 'StoredMessage_embedding_vector'
                RETURN options.indexConfig['vector.dimensions']
                """.trimIndent(),
            )
            .transform(Long::class.java),
    ).single().toInt()

    private companion object {
        const val NARROW = 4
        const val WIDE = 8
        const val POISON = "POISON"
    }
}
