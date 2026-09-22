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
    }
}
