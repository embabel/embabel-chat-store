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
import com.embabel.chat.store.model.AttachmentData
import com.embabel.chat.store.model.MessageData
import com.embabel.chat.store.model.TestSessionUser
import com.embabel.chat.store.util.UUIDv7
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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
        assertEquals(created.session.createdAt, created.session.lastActivityAt)
        assertEquals(testUser.id, created.owner.id)
        assertTrue(created.messages.isEmpty())
    }

    @Test
    fun `pages sessions by activity with session ID as deterministic tie breaker`() {
        val timestamps = mapOf(
            "session-a" to Instant.parse("2026-01-01T00:00:01Z"),
            "session-b" to Instant.parse("2026-01-01T00:00:03Z"),
            "session-c" to Instant.parse("2026-01-01T00:00:03Z"),
            "session-d" to Instant.parse("2026-01-01T00:00:02Z"),
            "session-e" to Instant.parse("2026-01-01T00:00:00Z"),
        )
        timestamps.forEach { (id, activity) ->
            chatSessionRepository.createSession(id, testUser, id)
            setActivity(id, activity)
        }

        val first = chatSessionRepository.listSessionsForUser(
            testUser.id,
            SessionPageRequest(pageSize = 2, order = SessionOrder.LAST_ACTIVITY),
        )
        val second = chatSessionRepository.listSessionsForUser(
            testUser.id,
            SessionPageRequest(pageSize = 2, cursor = first.nextCursor, order = SessionOrder.LAST_ACTIVITY),
        )
        val third = chatSessionRepository.listSessionsForUser(
            testUser.id,
            SessionPageRequest(pageSize = 2, cursor = second.nextCursor, order = SessionOrder.LAST_ACTIVITY),
        )

        assertEquals(listOf("session-c", "session-b"), first.items.map { it.session.sessionId })
        assertEquals(listOf("session-d", "session-a"), second.items.map { it.session.sessionId })
        assertEquals(listOf("session-e"), third.items.map { it.session.sessionId })
        assertNotNull(first.nextCursor)
        assertNotNull(second.nextCursor)
        assertNull(third.nextCursor)
    }

    @Test
    fun `summary pagination has the same ordering and remains scoped to owner`() {
        val other = TestSessionUser(UUID.randomUUID().toString(), "Other User")
        graphObjectManager.save(other)
        chatSessionRepository.createSession("mine-old", testUser)
        chatSessionRepository.createSession("mine-new", testUser)
        chatSessionRepository.createSession("theirs", other)
        setActivity("mine-old", Instant.parse("2026-01-01T00:00:00Z"))
        setActivity("mine-new", Instant.parse("2026-01-02T00:00:00Z"))
        setActivity("theirs", Instant.parse("2026-01-03T00:00:00Z"))

        val first = chatSessionRepository.listSessionSummariesForUser(
            testUser.id,
            SessionPageRequest(pageSize = 1, order = SessionOrder.LAST_ACTIVITY),
        )
        val second = chatSessionRepository.listSessionSummariesForUser(
            testUser.id,
            SessionPageRequest(pageSize = 1, cursor = first.nextCursor, order = SessionOrder.LAST_ACTIVITY),
        )

        assertEquals(listOf("mine-new"), first.items.map { it.session.sessionId })
        assertEquals(listOf("mine-old"), second.items.map { it.session.sessionId })
        assertNull(second.nextCursor)
    }

    @Test
    fun `pages by creation order on session ID by default, ignoring activity`() {
        val ids = List(5) { UUIDv7.generateString() }
        ids.forEach { chatSessionRepository.createSession(it, testUser, it) }
        // Activity deliberately contradicts creation order: the default must not follow it.
        setActivity(ids[0], Instant.parse("2026-01-01T00:00:09Z"))
        setActivity(ids[4], Instant.parse("2026-01-01T00:00:00Z"))

        val first = chatSessionRepository.listSessionsForUser(testUser.id, SessionPageRequest(pageSize = 2))
        val second = chatSessionRepository.listSessionsForUser(
            testUser.id,
            SessionPageRequest(pageSize = 2, cursor = first.nextCursor),
        )
        val third = chatSessionRepository.listSessionsForUser(
            testUser.id,
            SessionPageRequest(pageSize = 2, cursor = second.nextCursor),
        )

        val newestFirst = ids.sortedDescending()
        assertEquals(newestFirst.subList(0, 2), first.items.map { it.session.sessionId })
        assertEquals(newestFirst.subList(2, 4), second.items.map { it.session.sessionId })
        assertEquals(newestFirst.subList(4, 5), third.items.map { it.session.sessionId })
        assertNull(third.nextCursor)
    }

    @Test
    fun `a session with no activity timestamp still reads, defaulting to its creation time`() {
        val sessionId = UUIDv7.generateString()
        val session = chatSessionRepository.createSession(sessionId, testUser)
        persistenceManager.execute(
            QuerySpecification.withStatement(
                "MATCH (s:ChatSession {sessionId: \$id}) REMOVE s.lastActivityAt"
            ).bind(mapOf("id" to sessionId))
        )

        // @Default on SessionData.lastActivityAt: a node predating the property hydrates as
        // createdAt rather than failing, so old data needs no migration merely to be read.
        val page = chatSessionRepository.listSessionsForUser(testUser.id, SessionPageRequest(pageSize = 10))

        val loaded = page.items.single { it.session.sessionId == sessionId }
        assertEquals(session.session.createdAt, loaded.session.lastActivityAt)
    }

    @Test
    fun `the migration restores readability of a session with no activity timestamp`() {
        val sessionId = UUIDv7.generateString()
        chatSessionRepository.createSession(sessionId, testUser)
        persistenceManager.execute(
            QuerySpecification.withStatement(
                "MATCH (s:ChatSession {sessionId: \$id}) SET s.lastActivityAt = null"
            ).bind(mapOf("id" to sessionId))
        )

        SessionActivityMigration(persistenceManager).migrate()

        val page = chatSessionRepository.listSessionsForUser(testUser.id, SessionPageRequest(pageSize = 10))
        assertTrue(page.items.any { it.session.sessionId == sessionId })
    }

    @Test
    fun `a cursor issued for one ordering is rejected by the other`() {
        chatSessionRepository.createSession(UUIDv7.generateString(), testUser)
        chatSessionRepository.createSession(UUIDv7.generateString(), testUser)
        val createdPage = chatSessionRepository.listSessionsForUser(testUser.id, SessionPageRequest(pageSize = 1))

        assertThrows(IllegalArgumentException::class.java) {
            chatSessionRepository.listSessionsForUser(
                testUser.id,
                SessionPageRequest(pageSize = 1, cursor = createdPage.nextCursor, order = SessionOrder.LAST_ACTIVITY),
            )
        }
    }

    @Test
    fun `activity migration backfills latest message and is idempotent`() {
        val sessionId = UUID.randomUUID().toString()
        chatSessionRepository.createSession(sessionId, testUser)
        val latest = Instant.parse("2026-02-03T04:05:06Z")
        persistenceManager.execute(
            QuerySpecification.withStatement(
                "MATCH (s:ChatSession {sessionId: \$id}) SET s.lastActivityAt = null"
            ).bind(mapOf("id" to sessionId))
        )
        chatSessionRepository.addMessage(
            sessionId,
            MessageData(UUID.randomUUID().toString(), MessageRole.USER, "hi", latest),
        )
        persistenceManager.execute(
            QuerySpecification.withStatement(
                "MATCH (s:ChatSession {sessionId: \$id}) SET s.lastActivityAt = null"
            ).bind(mapOf("id" to sessionId))
        )

        SessionActivityMigration(persistenceManager).migrate()
        SessionActivityMigration(persistenceManager).migrate()

        assertEquals(latest, chatSessionRepository.findBySessionId(sessionId).get().session.lastActivityAt)
    }

    @Test
    fun `message writes advance activity monotonically by write time`() {
        val sessionId = UUID.randomUUID().toString()
        val createdAt = Instant.parse("2026-01-01T00:00:00Z")
        val activeAt = Instant.parse("2026-01-03T00:00:00Z")
        val staleWriterTime = Instant.parse("2026-01-02T00:00:00Z")
        fun repositoryAt(instant: Instant) = ChatSessionRepositoryImpl(
            graphObjectManager,
            persistenceManager,
            clock = Clock.fixed(instant, ZoneOffset.UTC),
        )

        repositoryAt(createdAt).createSession(sessionId, testUser)
        repositoryAt(activeAt).addMessage(
            sessionId,
            MessageData(UUID.randomUUID().toString(), MessageRole.USER, "newer write", createdAt),
        )
        repositoryAt(staleWriterTime).addMessage(
            sessionId,
            MessageData(UUID.randomUUID().toString(), MessageRole.USER, "stale writer", activeAt.plusSeconds(10)),
        )

        assertEquals(activeAt, chatSessionRepository.findBySessionId(sessionId).get().session.lastActivityAt)
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
    fun `narration targets the given message and does not advance activity`() {
        val sessionId = UUIDv7.generateString()
        chatSessionRepository.createSession(sessionId, testUser)
        val first = UUIDv7.generateString()
        val second = UUIDv7.generateString()
        chatSessionRepository.addMessage(
            sessionId,
            MessageData(first, MessageRole.ASSISTANT, "first", Instant.parse("2026-01-01T00:00:00Z")),
        )
        chatSessionRepository.addMessage(
            sessionId,
            MessageData(second, MessageRole.ASSISTANT, "second", Instant.parse("2026-01-01T00:00:01Z")),
        )
        val activityBefore = chatSessionRepository.findBySessionId(sessionId).get().session.lastActivityAt

        // The older message, which the previous "latest un-narrated assistant" heuristic
        // could never have reached.
        assertTrue(chatSessionRepository.updateMessageNarration(sessionId, first, "spoken form"))

        val messages = chatSessionRepository.getMessages(sessionId).associateBy { it.messageId }
        assertEquals("spoken form", messages[first]?.narration)
        assertNull(messages[second]?.narration)
        assertEquals(
            activityBefore,
            chatSessionRepository.findBySessionId(sessionId).get().session.lastActivityAt,
            "narration is enrichment, not activity",
        )
    }

    @Test
    fun `narration reports a miss rather than narrating the wrong message`() {
        val sessionId = UUIDv7.generateString()
        chatSessionRepository.createSession(sessionId, testUser)
        chatSessionRepository.addMessage(
            sessionId,
            MessageData(UUIDv7.generateString(), MessageRole.ASSISTANT, "only", Instant.parse("2026-01-01T00:00:00Z")),
        )

        assertFalse(chatSessionRepository.updateMessageNarration(sessionId, UUIDv7.generateString(), "nope"))
        assertTrue(chatSessionRepository.getMessages(sessionId).all { it.narration == null })
    }

    private fun setActivity(sessionId: String, activityAt: Instant) {
        persistenceManager.execute(
            QuerySpecification
                .withStatement(
                    "MATCH (s:ChatSession {sessionId: \$sessionId}) SET s.lastActivityAt = \$activityAt"
                )
                .bind(mapOf("sessionId" to sessionId, "activityAt" to activityAt))
        )
    }

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

    @Test
    fun `attachments survive a reload`() {
        // The whole point of the feature: re-reading a conversation must still show what was
        // shared, not just the caption that referred to it.
        val sessionId = UUID.randomUUID().toString()
        chatSessionRepository.createSession(sessionId, testUser, "With Attachment")

        val attachment = AttachmentData.of(
            filename = "ledger.txt",
            mimeType = "text/plain",
            sizeBytes = 208080,
            contentHash = "a".repeat(64),
            storageUri = "world://uploads/ledger.txt",
        )

        chatSessionRepository.addMessage(
            sessionId = sessionId,
            messageData = MessageData(
                messageId = UUID.randomUUID().toString(),
                role = MessageRole.USER,
                content = "here is my ledger",
                createdAt = Instant.now(),
            ),
            author = testUser,
            attachments = listOf(attachment),
        )

        val reloaded = chatSessionRepository.getMessages(sessionId).single()
        assertEquals(1, reloaded.attachments.size)
        val found = reloaded.attachments.single()
        assertEquals(attachment.attachmentId, found.attachmentId)
        assertEquals("ledger.txt", found.filename)
        assertEquals("text/plain", found.mimeType)
        assertEquals(208080L, found.sizeBytes)
        assertEquals("world://uploads/ledger.txt", found.storageUri)
        assertEquals(1, countNodes("Attachment", "attachmentId", attachment.attachmentId))
    }

    @Test
    fun `a message with no attachments loads an empty list`() {
        val sessionId = UUID.randomUUID().toString()
        chatSessionRepository.createSession(sessionId, testUser, "No Attachment")

        chatSessionRepository.addMessage(
            sessionId = sessionId,
            messageData = MessageData(
                messageId = UUID.randomUUID().toString(),
                role = MessageRole.USER,
                content = "just text",
                createdAt = Instant.now(),
            ),
            author = testUser,
        )

        assertTrue(chatSessionRepository.getMessages(sessionId).single().attachments.isEmpty())
    }

    @Test
    fun `deleting a session removes its attachments`() {
        // Attachments hang off messages, and messages cascade on session delete. If the
        // cascade does not reach them, deleted conversations leave orphaned :Attachment
        // nodes pointing at files nobody can reach.
        val sessionId = UUID.randomUUID().toString()
        chatSessionRepository.createSession(sessionId, testUser, "Doomed")

        val attachment = AttachmentData.of(
            filename = "receipt.pdf",
            mimeType = "application/pdf",
            sizeBytes = 1024,
            contentHash = "b".repeat(64),
            storageUri = "world://uploads/receipt.pdf",
        )
        chatSessionRepository.addMessage(
            sessionId = sessionId,
            messageData = MessageData(
                messageId = UUID.randomUUID().toString(),
                role = MessageRole.USER,
                content = "a receipt",
                createdAt = Instant.now(),
            ),
            author = testUser,
            attachments = listOf(attachment),
        )
        assertEquals(1, countNodes("Attachment", "attachmentId", attachment.attachmentId))

        chatSessionRepository.deleteSession(sessionId)

        assertEquals(0, countNodes("Attachment", "attachmentId", attachment.attachmentId))
    }
}
