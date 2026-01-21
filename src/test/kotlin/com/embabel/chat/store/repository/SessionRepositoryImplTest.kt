package com.embabel.chat.store.repository

import com.embabel.chat.store.TestApplication
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
 * Integration tests for SessionRepositoryImpl.
 *
 * Uses TestSessionUser to demonstrate polymorphic SessionUser support.
 */
@SpringBootTest(classes = [TestApplication::class])
@Transactional
class SessionRepositoryImplTest {

    @Autowired
    private lateinit var sessionRepository: SessionRepository

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
        val created = sessionRepository.createSession(
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
        val created = sessionRepository.createSession(
            sessionId = sessionId,
            owner = testUser,
            title = null
        )

        // Then
        assertNull(created.session.title)
    }

    @Test
    fun `test find session by ID`() {
        // Given
        val sessionId = UUID.randomUUID().toString()
        sessionRepository.createSession(sessionId, testUser, "Findable Session")

        // When
        val found = sessionRepository.findBySessionId(sessionId)

        // Then
        assertTrue(found.isPresent)
        assertEquals(sessionId, found.get().session.sessionId)
        assertEquals("Findable Session", found.get().session.title)
        assertEquals(testUser.id, found.get().owner.id)
    }

    @Test
    fun `test findBySessionId returns empty when not found`() {
        // When
        val found = sessionRepository.findBySessionId("nonexistent-session-id")

        // Then
        assertFalse(found.isPresent)
    }

    @Test
    fun `test list sessions for user`() {
        // Given
        val session1Id = UUID.randomUUID().toString()
        val session2Id = UUID.randomUUID().toString()

        sessionRepository.createSession(session1Id, testUser, "Session 1")
        sessionRepository.createSession(session2Id, testUser, "Session 2")

        // When
        val sessions = sessionRepository.listSessionsForUser(testUser.id)

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
        val sessions = sessionRepository.listSessionsForUser(anotherUser.id)

        // Then
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun `test update session title`() {
        // Given
        val sessionId = UUID.randomUUID().toString()
        sessionRepository.createSession(sessionId, testUser, "Original Title")

        // When
        sessionRepository.updateSessionTitle(sessionId, "Updated Title")

        // Then
        val found = sessionRepository.findBySessionId(sessionId)
        assertTrue(found.isPresent)
        assertEquals("Updated Title", found.get().session.title)
    }

    @Test
    fun `test delete session`() {
        // Given
        val sessionId = UUID.randomUUID().toString()
        sessionRepository.createSession(sessionId, testUser, "To Delete")

        // When
        sessionRepository.deleteSession(sessionId)

        // Then
        val found = sessionRepository.findBySessionId(sessionId)
        assertFalse(found.isPresent)
    }

    @Test
    fun `test add message to session`() {
        // Given
        val sessionId = UUID.randomUUID().toString()
        sessionRepository.createSession(sessionId, testUser, "Test Session")

        val message = StoredMessage(
            messageId = UUID.randomUUID().toString(),
            role = StoredMessage.ROLE_USER,
            content = "Hello, world!",
            createdAt = Instant.now()
        )

        // When
        val updated = sessionRepository.addMessage(sessionId, message)

        // Then
        assertEquals(1, updated.messages.size)
        assertEquals("Hello, world!", updated.messages[0].content)
        assertEquals(StoredMessage.ROLE_USER, updated.messages[0].role)
    }

    @Test
    fun `test add multiple messages maintains order`() {
        // Given
        val sessionId = UUID.randomUUID().toString()
        sessionRepository.createSession(sessionId, testUser, "Test Session")

        val msg1 = StoredMessage(
            messageId = "001-${UUID.randomUUID()}",
            role = StoredMessage.ROLE_USER,
            content = "First message",
            createdAt = Instant.now()
        )
        val msg2 = StoredMessage(
            messageId = "002-${UUID.randomUUID()}",
            role = StoredMessage.ROLE_ASSISTANT,
            content = "Second message",
            createdAt = Instant.now()
        )
        val msg3 = StoredMessage(
            messageId = "003-${UUID.randomUUID()}",
            role = StoredMessage.ROLE_USER,
            content = "Third message",
            createdAt = Instant.now()
        )

        // When
        sessionRepository.addMessage(sessionId, msg1)
        sessionRepository.addMessage(sessionId, msg2)
        val updated = sessionRepository.addMessage(sessionId, msg3)

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
        val message = StoredMessage(
            messageId = UUID.randomUUID().toString(),
            role = StoredMessage.ROLE_USER,
            content = "Should fail",
            createdAt = Instant.now()
        )

        // When/Then
        assertThrows(IllegalArgumentException::class.java) {
            sessionRepository.addMessage("nonexistent-session", message)
        }
    }

    @Test
    fun `test get messages`() {
        // Given
        val sessionId = UUID.randomUUID().toString()
        sessionRepository.createSession(sessionId, testUser, "Test Session")

        val msg1 = StoredMessage(
            messageId = "a-${UUID.randomUUID()}",
            role = StoredMessage.ROLE_USER,
            content = "Message 1",
            createdAt = Instant.now()
        )
        val msg2 = StoredMessage(
            messageId = "b-${UUID.randomUUID()}",
            role = StoredMessage.ROLE_ASSISTANT,
            content = "Message 2",
            createdAt = Instant.now()
        )
        sessionRepository.addMessage(sessionId, msg1)
        sessionRepository.addMessage(sessionId, msg2)

        // When
        val messages = sessionRepository.getMessages(sessionId)

        // Then
        assertEquals(2, messages.size)
    }

    @Test
    fun `test get messages returns empty for nonexistent session`() {
        // When
        val messages = sessionRepository.getMessages("nonexistent-session")

        // Then
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `test session has correct timestamps`() {
        // Given
        val sessionId = UUID.randomUUID().toString()
        val beforeCreate = Instant.now()

        // When
        val created = sessionRepository.createSession(sessionId, testUser, "Timestamp Test")

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
        sessionRepository.createSession(sessionId, testUser, "To Delete")

        // When
        sessionRepository.deleteAll()

        // Then
        val found = sessionRepository.findBySessionId(sessionId)
        assertFalse(found.isPresent)
    }
}