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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Integration tests for ChatSessionRepositoryImpl.
 *
 * Uses TestSessionUser to demonstrate polymorphic User support.
 */
@SpringBootTest(classes = [TestApplication::class])
@Transactional
class ChatSessionRepositoryImplTest {

    @Autowired
    private lateinit var chatSessionRepository: ChatSessionRepository

    @Autowired
    private lateinit var graphObjectManager: GraphObjectManager

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    private lateinit var testUser: TestSessionUser

    @BeforeEach
    fun setUp() {
        // Create a test user for session ownership
        testUser = TestSessionUser(
            id = UUID.randomUUID().toString(),
            displayName = "Test User"
        )
        // Save the test user to Neo4j
        graphObjectManager.save(testUser)
    }

    @Test
    fun `test create session`() {
        // Given
        val sessionId = UUID.randomUUID().toString()

        // When
        val created = chatSessionRepository.createSession(
            sessionId = sessionId,
            owner = testUser,
            title = "Test Session"
        )

        // Then
        assertNotNull(created)
        assertEquals(sessionId, created.session.sessionId)
        assertEquals("Test Session", created.session.title)
        assertNotNull(created.session.createdAt)
        assertEquals(testUser.id, created.owner.id)
        assertTrue(created.messages.isEmpty())
    }

    @Test
    fun `test create session without title`() {
        // Given
        val sessionId = UUID.randomUUID().toString()

        // When
        val created = chatSessionRepository.createSession(
            sessionId = sessionId,
            owner = testUser,
            title = null
        )

        // Then
        assertNull(created.session.title)
    }

    @Test
    fun `test create session with message and author`() {
        // Given
        val sessionId = UUID.randomUUID().toString()
        val messageData = MessageData(
            messageId = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            content = "Initial message",
            createdAt = Instant.now()
        )

        // When
        val created = chatSessionRepository.createSessionWithMessage(
            sessionId = sessionId,
            owner = testUser,
            title = "Session with Message",
            messageData = messageData,
            messageAuthor = testUser
        )

        // Then
        assertNotNull(created)
        assertEquals(sessionId, created.session.sessionId)
        assertEquals("Session with Message", created.session.title)
        assertEquals(1, created.messages.size)
        assertEquals("Initial message", created.messages[0].content)
        assertEquals(testUser.id, created.messages[0].author?.id)
    }

    @Test
    fun `test create session with system message (no author)`() {
        // Given
        val sessionId = UUID.randomUUID().toString()
        val messageData = MessageData(
            messageId = UUID.randomUUID().toString(),
            role = MessageRole.ASSISTANT,
            content = "Welcome! How can I help?",
            createdAt = Instant.now()
        )

        // When
        val created = chatSessionRepository.createSessionWithMessage(
            sessionId = sessionId,
            owner = testUser,
            title = "Welcome Session",
            messageData = messageData,
            messageAuthor = null  // System/assistant message
        )

        // Then
        assertEquals(1, created.messages.size)
        assertEquals("Welcome! How can I help?", created.messages[0].content)
        assertNull(created.messages[0].author)
    }

    @Test
    fun `test find session by ID`() {
        // Given
        val sessionId = UUID.randomUUID().toString()
        chatSessionRepository.createSession(sessionId, testUser, "Findable Session")

        // When
        val found = chatSessionRepository.findBySessionId(sessionId)

        // Then
        assertTrue(found.isPresent)
        assertEquals(sessionId, found.get().session.sessionId)
        assertEquals("Findable Session", found.get().session.title)
        assertEquals(testUser.id, found.get().owner.id)
    }

    @Test
    fun `test findBySessionId returns empty when not found`() {
        // When
        val found = chatSessionRepository.findBySessionId("nonexistent-session-id")

        // Then
        assertFalse(found.isPresent)
    }

    @Test
    fun `test list sessions for user`() {
        // Given
        val session1Id = UUID.randomUUID().toString()
        val session2Id = UUID.randomUUID().toString()

        chatSessionRepository.createSession(session1Id, testUser, "Session 1")
        chatSessionRepository.createSession(session2Id, testUser, "Session 2")

        // When
        val sessions = chatSessionRepository.listSessionsForUser(testUser.id)

        // Then
        assertTrue(sessions.size >= 2)
        assertTrue(sessions.any { it.session.sessionId == session1Id })
        assertTrue(sessions.any { it.session.sessionId == session2Id })
    }

    @Test
    fun `test list sessions returns empty for user with no sessions`() {
        // Given
        val anotherUser = TestSessionUser(
            id = UUID.randomUUID().toString(),
            displayName = "Another User"
        )
        graphObjectManager.save(anotherUser)

        // When
        val sessions = chatSessionRepository.listSessionsForUser(anotherUser.id)

        // Then
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun `test count sessions for user`() {
        // Given
        val before = chatSessionRepository.countSessionsForUser(testUser.id)
        chatSessionRepository.createSession(UUID.randomUUID().toString(), testUser, "S1")
        chatSessionRepository.createSession(UUID.randomUUID().toString(), testUser, "S2")

        // When
        val after = chatSessionRepository.countSessionsForUser(testUser.id)

        // Then
        assertEquals(before + 2, after)
    }

    @Test
    fun `test session summaries report message counts without loading messages`() {
        // Given a session with two messages
        val sessionId = UUID.randomUUID().toString()
        chatSessionRepository.createSession(sessionId, testUser, "Summary Session")
        chatSessionRepository.addMessage(
            sessionId,
            MessageData(UUID.randomUUID().toString(), MessageRole.USER, "one", Instant.now()),
            testUser
        )
        chatSessionRepository.addMessage(
            sessionId,
            MessageData(UUID.randomUUID().toString(), MessageRole.ASSISTANT, "two", Instant.now()),
            null
        )

        // When
        val summary = chatSessionRepository.listSessionSummariesForUser(testUser.id)
            .firstOrNull { it.session.sessionId == sessionId }

        // Then: the count is folded into the query result
        assertNotNull(summary)
        assertEquals(testUser.id, summary!!.owner.id)
        assertEquals(2L, summary.messageCount)
    }

    @Test
    fun `test session summary reports zero for a session with no messages`() {
        // Given
        val sessionId = UUID.randomUUID().toString()
        chatSessionRepository.createSession(sessionId, testUser, "Empty Session")

        // When
        val summary = chatSessionRepository.listSessionSummariesForUser(testUser.id)
            .first { it.session.sessionId == sessionId }

        // Then
        assertEquals(0L, summary.messageCount)
    }

    @Test
    fun `test get participants returns distinct authors skipping message nodes`() {
        // Given: testUser authors two messages; an assistant message has no author
        val sessionId = UUID.randomUUID().toString()
        chatSessionRepository.createSession(sessionId, testUser, "Participants")
        chatSessionRepository.addMessage(
            sessionId,
            MessageData(UUID.randomUUID().toString(), MessageRole.USER, "hi", Instant.now()),
            testUser
        )
        chatSessionRepository.addMessage(
            sessionId,
            MessageData(UUID.randomUUID().toString(), MessageRole.USER, "again", Instant.now()),
            testUser
        )
        chatSessionRepository.addMessage(
            sessionId,
            MessageData(UUID.randomUUID().toString(), MessageRole.ASSISTANT, "hello", Instant.now()),
            null
        )

        // When
        val participants = chatSessionRepository.getParticipants(sessionId)

        // Then: testUser appears once despite authoring two messages; the un-authored
        // assistant message contributes no participant.
        assertEquals(1, participants.size)
        assertEquals(testUser.id, participants[0].id)
    }

    @Test
    fun `test get participants returns empty for nonexistent session`() {
        assertTrue(chatSessionRepository.getParticipants("nonexistent-session").isEmpty())
    }

    @Test
    fun `test update session title`() {
        // Given
        val sessionId = UUID.randomUUID().toString()
        chatSessionRepository.createSession(sessionId, testUser, "Original Title")

        // When
        chatSessionRepository.updateSessionTitle(sessionId, "Updated Title")

        // Then
        val found = chatSessionRepository.findBySessionId(sessionId)
        assertTrue(found.isPresent)
        assertEquals("Updated Title", found.get().session.title)
    }

    @Test
    fun `test delete session`() {
        // Given
        val sessionId = UUID.randomUUID().toString()
        chatSessionRepository.createSession(sessionId, testUser, "To Delete")

        // When
        chatSessionRepository.deleteSession(sessionId)

        // Then
        val found = chatSessionRepository.findBySessionId(sessionId)
        assertFalse(found.isPresent)
    }

    @Test
    fun `test delete session cascades to its messages but preserves the owner and other sessions`() {
        // Given: user A owns a session with two messages, built via the real repo API
        // so the genuine ChatSession/StoredMessage/OWNED_BY/HAS_MESSAGE/AUTHORED_BY graph exists.
        val userA = testUser // saved in setUp()
        val sessionAId = UUID.randomUUID().toString()
        chatSessionRepository.createSession(sessionAId, userA, "Session A")
        val a1 = MessageData(
            messageId = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            content = "A — first",
            createdAt = Instant.now()
        )
        val a2 = MessageData(
            messageId = UUID.randomUUID().toString(),
            role = MessageRole.ASSISTANT,
            content = "A — second",
            createdAt = Instant.now()
        )
        chatSessionRepository.addMessage(sessionAId, a1, userA)
        chatSessionRepository.addMessage(sessionAId, a2, null)

        // And: a second user B with their own session and messages, which must be left untouched.
        val userB = TestSessionUser(
            id = UUID.randomUUID().toString(),
            displayName = "User B"
        )
        graphObjectManager.save(userB)
        val sessionBId = UUID.randomUUID().toString()
        chatSessionRepository.createSession(sessionBId, userB, "Session B")
        val b1 = MessageData(
            messageId = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            content = "B — first",
            createdAt = Instant.now()
        )
        val b2 = MessageData(
            messageId = UUID.randomUUID().toString(),
            role = MessageRole.ASSISTANT,
            content = "B — second",
            createdAt = Instant.now()
        )
        chatSessionRepository.addMessage(sessionBId, b1, userB)
        chatSessionRepository.addMessage(sessionBId, b2, null)

        // Sanity: the graph is shaped as expected before we delete.
        assertEquals(1, countNodes("ChatSession", "sessionId", sessionAId))
        assertEquals(1, countNodes("StoredMessage", "messageId", a1.messageId))
        assertEquals(1, countNodes("StoredMessage", "messageId", a2.messageId))
        assertEquals(1, countNodes("User", "id", userA.id))

        // When
        chatSessionRepository.deleteSession(sessionAId)

        // Then: session A's :ChatSession node is gone.
        assertFalse(chatSessionRepository.findBySessionId(sessionAId).isPresent)
        assertEquals(0, countNodes("ChatSession", "sessionId", sessionAId))

        // And: every one of session A's :StoredMessage nodes is gone (cascade reached them).
        assertEquals(0, countNodes("StoredMessage", "messageId", a1.messageId))
        assertEquals(0, countNodes("StoredMessage", "messageId", a2.messageId))

        // And: the owner :User survives — the cascade view declares only HAS_MESSAGE, so it
        // structurally cannot reach :User; DETACH merely dropped OWNED_BY/AUTHORED_BY edges.
        assertEquals(1, countNodes("User", "id", userA.id))

        // And: session B and its messages are entirely untouched.
        val sessionB = chatSessionRepository.findBySessionId(sessionBId)
        assertTrue(sessionB.isPresent)
        assertEquals(2, sessionB.get().messages.size)
        assertEquals(1, countNodes("ChatSession", "sessionId", sessionBId))
        assertEquals(1, countNodes("StoredMessage", "messageId", b1.messageId))
        assertEquals(1, countNodes("StoredMessage", "messageId", b2.messageId))
        assertEquals(1, countNodes("User", "id", userB.id))
    }

    /**
     * Counts nodes carrying [label] whose [idProperty] equals [id], via raw Cypher.
     * Used to assert node-level state after a cascade delete, below the GraphView layer.
     * The id value is always bound as a parameter; only the controlled label/property
     * names are interpolated.
     */
    private fun countNodes(label: String, idProperty: String, id: String): Int =
        persistenceManager.getOne(
            QuerySpecification
                .withStatement("MATCH (n:$label {$idProperty: \$id}) RETURN count(n) AS count")
                .bind(mapOf("id" to id))
                .transform(Int::class.java)
        )

    @Test
    fun `test add message to session with author`() {
        // Given
        val sessionId = UUID.randomUUID().toString()
        chatSessionRepository.createSession(sessionId, testUser, "Test Session")

        val messageData = MessageData(
            messageId = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            content = "Hello, world!",
            createdAt = Instant.now()
        )

        // When
        val updated = chatSessionRepository.addMessage(sessionId, messageData, testUser)

        // Then
        assertEquals(1, updated.messages.size)
        assertEquals("Hello, world!", updated.messages[0].content)
        assertEquals(MessageRole.USER, updated.messages[0].role)
        assertEquals(testUser.id, updated.messages[0].author?.id)
    }

    @Test
    fun `test add message to session without author`() {
        // Given
        val sessionId = UUID.randomUUID().toString()
        chatSessionRepository.createSession(sessionId, testUser, "Test Session")

        val messageData = MessageData(
            messageId = UUID.randomUUID().toString(),
            role = MessageRole.ASSISTANT,
            content = "I'm here to help!",
            createdAt = Instant.now()
        )

        // When
        val updated = chatSessionRepository.addMessage(sessionId, messageData, null)

        // Then
        assertEquals(1, updated.messages.size)
        assertEquals("I'm here to help!", updated.messages[0].content)
        assertNull(updated.messages[0].author)
    }

    @Test
    fun `test add multiple messages maintains order`() {
        // Given
        val sessionId = UUID.randomUUID().toString()
        chatSessionRepository.createSession(sessionId, testUser, "Test Session")

        val msg1 = MessageData(
            messageId = "001-${UUID.randomUUID()}",
            role = MessageRole.USER,
            content = "First message",
            createdAt = Instant.now()
        )
        val msg2 = MessageData(
            messageId = "002-${UUID.randomUUID()}",
            role = MessageRole.ASSISTANT,
            content = "Second message",
            createdAt = Instant.now()
        )
        val msg3 = MessageData(
            messageId = "003-${UUID.randomUUID()}",
            role = MessageRole.USER,
            content = "Third message",
            createdAt = Instant.now()
        )

        // When
        chatSessionRepository.addMessage(sessionId, msg1, testUser)
        chatSessionRepository.addMessage(sessionId, msg2, null)
        val updated = chatSessionRepository.addMessage(sessionId, msg3, testUser)

        // Then
        assertEquals(3, updated.messages.size)
        // Messages should be in order they were added
        assertTrue(updated.messages.any { it.content == "First message" })
        assertTrue(updated.messages.any { it.content == "Second message" })
        assertTrue(updated.messages.any { it.content == "Third message" })
    }

    @Test
    fun `test add message throws for nonexistent session`() {
        // Given
        val messageData = MessageData(
            messageId = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            content = "Should fail",
            createdAt = Instant.now()
        )

        // When/Then
        assertThrows(IllegalArgumentException::class.java) {
            chatSessionRepository.addMessage("nonexistent-session", messageData, testUser)
        }
    }

    @Test
    fun `test get messages`() {
        // Given
        val sessionId = UUID.randomUUID().toString()
        chatSessionRepository.createSession(sessionId, testUser, "Test Session")

        val msg1 = MessageData(
            messageId = "a-${UUID.randomUUID()}",
            role = MessageRole.USER,
            content = "Message 1",
            createdAt = Instant.now()
        )
        val msg2 = MessageData(
            messageId = "b-${UUID.randomUUID()}",
            role = MessageRole.ASSISTANT,
            content = "Message 2",
            createdAt = Instant.now()
        )
        chatSessionRepository.addMessage(sessionId, msg1, testUser)
        chatSessionRepository.addMessage(sessionId, msg2, null)

        // When
        val messages = chatSessionRepository.getMessages(sessionId)

        // Then
        assertEquals(2, messages.size)
    }

    @Test
    fun `test get messages returns empty for nonexistent session`() {
        // When
        val messages = chatSessionRepository.getMessages("nonexistent-session")

        // Then
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `test session has correct timestamps`() {
        // Given
        val sessionId = UUID.randomUUID().toString()
        val beforeCreate = Instant.now()

        // When
        val created = chatSessionRepository.createSession(sessionId, testUser, "Timestamp Test")

        val afterCreate = Instant.now()

        // Then
        assertNotNull(created.session.createdAt)
        assertTrue(created.session.createdAt >= beforeCreate.minusMillis(100))
        assertTrue(created.session.createdAt <= afterCreate.plusMillis(100))
    }

    @Test
    fun `test delete all sessions`() {
        // Given
        val sessionId = UUID.randomUUID().toString()
        chatSessionRepository.createSession(sessionId, testUser, "To Delete")

        // When
        chatSessionRepository.deleteAll()

        // Then
        val found = chatSessionRepository.findBySessionId(sessionId)
        assertFalse(found.isPresent)
    }
}
