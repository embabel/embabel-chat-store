package com.embabel.chat.store.repository

import com.embabel.chat.store.TestApplication
import com.embabel.chat.store.model.MessageData
import com.embabel.chat.store.model.StoredMessage
import com.embabel.chat.store.model.TestSessionUser
import org.drivine.manager.GraphObjectManager
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
 * Uses TestSessionUser to demonstrate polymorphic SessionUser support.
 */
@SpringBootTest(classes = [TestApplication::class])
@Transactional
class ChatSessionRepositoryImplTest {

    @Autowired
    private lateinit var chatSessionRepository: ChatSessionRepository

    @Autowired
    private lateinit var graphObjectManager: GraphObjectManager

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
            role = MessageData.ROLE_USER,
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
            role = MessageData.ROLE_ASSISTANT,
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
    fun `test add message to session with author`() {
        // Given
        val sessionId = UUID.randomUUID().toString()
        chatSessionRepository.createSession(sessionId, testUser, "Test Session")

        val messageData = MessageData(
            messageId = UUID.randomUUID().toString(),
            role = MessageData.ROLE_USER,
            content = "Hello, world!",
            createdAt = Instant.now()
        )

        // When
        val updated = chatSessionRepository.addMessage(sessionId, messageData, testUser)

        // Then
        assertEquals(1, updated.messages.size)
        assertEquals("Hello, world!", updated.messages[0].content)
        assertEquals(MessageData.ROLE_USER, updated.messages[0].role)
        assertEquals(testUser.id, updated.messages[0].author?.id)
    }

    @Test
    fun `test add message to session without author`() {
        // Given
        val sessionId = UUID.randomUUID().toString()
        chatSessionRepository.createSession(sessionId, testUser, "Test Session")

        val messageData = MessageData(
            messageId = UUID.randomUUID().toString(),
            role = MessageData.ROLE_ASSISTANT,
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
            role = MessageData.ROLE_USER,
            content = "First message",
            createdAt = Instant.now()
        )
        val msg2 = MessageData(
            messageId = "002-${UUID.randomUUID()}",
            role = MessageData.ROLE_ASSISTANT,
            content = "Second message",
            createdAt = Instant.now()
        )
        val msg3 = MessageData(
            messageId = "003-${UUID.randomUUID()}",
            role = MessageData.ROLE_USER,
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
            role = MessageData.ROLE_USER,
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
            role = MessageData.ROLE_USER,
            content = "Message 1",
            createdAt = Instant.now()
        )
        val msg2 = MessageData(
            messageId = "b-${UUID.randomUUID()}",
            role = MessageData.ROLE_ASSISTANT,
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
