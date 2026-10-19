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
import com.embabel.chat.store.model.DataAttachmentData
import com.embabel.chat.store.model.MessageData
import com.embabel.chat.store.model.TestSessionUser
import com.embabel.chat.store.util.UUIDv7
import org.drivine.manager.PersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.manager.load
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.VectorIndexSpec
import org.drivine.test.OracleCypherFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The repository on Oracle, which does not speak Cypher: everything it does must reach the store
 * through the object manager (embabel/embabel-chat-store#38).
 *
 * Runs only with `ORACLE_TESTS=true`, as Drivine's own Oracle tests do: the image is large and the
 * database wants two gigabytes.
 */
@SpringBootTest(classes = [TestApplication::class])
@ActiveProfiles("oracle")
@EnabledIfEnvironmentVariable(named = "ORACLE_TESTS", matches = "true")
class ChatSessionRepositoryOracleTest {

    @Autowired
    private lateinit var chatSessionRepository: ChatSessionRepository

    @Autowired
    private lateinit var graphObjectManager: StatelessGraphObjectManager

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    private lateinit var user: TestSessionUser

    @BeforeEach
    fun setUp() {
        OracleCypherFixtures.clear(persistenceManager)
        user = TestSessionUser(UUID.randomUUID().toString(), "Oracle User")
        graphObjectManager.save(user)
    }

    @Test
    fun `a session is created, found, retitled and deleted`() {
        val sessionId = UUIDv7.generateString()
        chatSessionRepository.createSessionWithMessage(sessionId, user, "First", message("hello"), user, null)

        chatSessionRepository.updateSessionTitle(sessionId, "Second")

        val found = chatSessionRepository.findBySessionId(sessionId).orElseThrow()
        assertEquals("Second", found.session.title)
        assertEquals(user.id, found.owner.id)
        assertEquals(listOf("hello"), found.messages.map { it.content })
        assertEquals(listOf(user.id), chatSessionRepository.getParticipants(sessionId).map { it.id })

        chatSessionRepository.deleteSession(sessionId)

        assertTrue(chatSessionRepository.findBySessionId(sessionId).isEmpty)
        assertNull(graphObjectManager.load(found.messages.single().messageId, MessageData::class.java))
    }

    @Test
    fun `messages are added in order and advance the session's activity, never backwards`() {
        val sessionId = UUIDv7.generateString()
        chatSessionRepository.createSession(sessionId, user, "Activity")
        val later = Instant.parse("2040-01-02T03:04:05Z")

        repositoryAt(later).addMessage(sessionId, message("first"), user)
        repositoryAt(later.minusSeconds(3600)).addMessage(sessionId, message("second"))

        val session = chatSessionRepository.findBySessionId(sessionId).orElseThrow()
        assertEquals(listOf("first", "second"), session.messages.map { it.content })
        assertEquals(later, session.session.lastActivityAt)
    }

    @Test
    fun `a message for a session that does not exist is refused and makes no session`() {
        val missing = UUIDv7.generateString()

        assertThrows(IllegalArgumentException::class.java) {
            chatSessionRepository.addMessage(missing, message("orphan"))
        }

        assertTrue(chatSessionRepository.findBySessionId(missing).isEmpty)
    }

    @Test
    fun `narration is written to the message of the session named, and to no other`() {
        val sessionId = UUIDv7.generateString()
        val other = UUIDv7.generateString()
        val narrated = message("to be spoken", MessageRole.ASSISTANT)
        chatSessionRepository.createSessionWithMessage(sessionId, user, "Narrated", narrated, null, null)
        chatSessionRepository.createSession(other, user, "Other")
        val activityBefore = chatSessionRepository.findBySessionId(sessionId).orElseThrow().session.lastActivityAt

        assertFalse(chatSessionRepository.updateMessageNarration(other, narrated.messageId, "wrong session"))
        assertFalse(chatSessionRepository.updateMessageNarration(sessionId, UUIDv7.generateString(), "no such message"))
        assertTrue(chatSessionRepository.updateMessageNarration(sessionId, narrated.messageId, "spoken form"))

        val session = chatSessionRepository.findBySessionId(sessionId).orElseThrow()
        assertEquals("spoken form", session.messages.single().narration)
        assertEquals("to be spoken", session.messages.single().content)
        assertEquals(activityBefore, session.session.lastActivityAt)
    }

    @Test
    fun `a reply keeps the message it answers across a reload`() {
        val sessionId = UUIDv7.generateString()
        chatSessionRepository.createSession(sessionId, user, "Replies")
        val question = message("question")
        chatSessionRepository.addMessage(sessionId, question)
        chatSessionRepository.addMessage(sessionId, message("answer", MessageRole.ASSISTANT).copy(inReplyTo = question.messageId))

        val messages = chatSessionRepository.getMessages(sessionId)
        assertEquals(listOf(null, question.messageId), messages.map { it.inReplyTo })
    }

    @Test
    fun `a message keeps every recipient and its data attachments across a reload, and loses them with its session`() {
        val sessionId = UUIDv7.generateString()
        chatSessionRepository.createSession(sessionId, user, "Several")
        val triage = TestSessionUser(UUID.randomUUID().toString(), "Triage").also { graphObjectManager.save(it) }
        val steward = TestSessionUser(UUID.randomUUID().toString(), "Steward").also { graphObjectManager.save(it) }
        val record = DataAttachmentData.of(type = "entity", title = "Customer Northwind", json = """{"id":"c-1"}""")
        chatSessionRepository.addMessage(
            sessionId,
            MessageWrite(message("look"), author = user, recipients = listOf(triage, steward), dataAttachments = listOf(record)),
        )

        val reloaded = chatSessionRepository.getMessages(sessionId).single()
        assertEquals(setOf(triage.id, steward.id), reloaded.recipients.map { it.id }.toSet())
        assertEquals(listOf(record.json), reloaded.dataAttachments.map { it.json })

        chatSessionRepository.deleteSession(sessionId)
        assertNull(graphObjectManager.load<DataAttachmentData>(record.dataAttachmentId))
    }

    @Test
    fun `sessions are listed, counted and summarised for their owner alone`() {
        val stranger = TestSessionUser(UUID.randomUUID().toString(), "Stranger")
        graphObjectManager.save(stranger)
        val first = UUIDv7.generateString()
        val second = UUIDv7.generateString()
        chatSessionRepository.createSessionWithMessage(first, user, "First", message("one"), user, null)
        chatSessionRepository.createSession(second, user, "Second")
        chatSessionRepository.createSession(UUIDv7.generateString(), stranger, "Theirs")

        assertEquals(2, chatSessionRepository.countSessionsForUser(user.id))
        assertEquals(listOf(second, first), chatSessionRepository.listSessionsForUser(user.id).map { it.session.sessionId })
        assertEquals(
            mapOf(first to 1L, second to 0L),
            chatSessionRepository.listSessionSummariesForUser(user.id).associate { it.session.sessionId to it.messageCount.toLong() },
        )
    }

    @Test
    fun `a session summary names who started the session, the author of its first message`() {
        val triage = TestSessionUser(UUID.randomUUID().toString(), "Triage").also { graphObjectManager.save(it) }
        val mine = UUIDv7.generateString()
        val theirs = UUIDv7.generateString()
        listOf(mine, theirs).forEach { chatSessionRepository.createSession(it, user) }
        chatSessionRepository.addMessage(mine, MessageWrite(message("hi"), user, listOf(triage)))
        chatSessionRepository.addMessage(mine, MessageWrite(message("hello", MessageRole.ASSISTANT), triage, listOf(user)))
        chatSessionRepository.addMessage(theirs, MessageWrite(message("Steward asks"), triage))
        chatSessionRepository.addMessage(theirs, MessageWrite(message("and I reply"), user))

        val startedBy = chatSessionRepository.listSessionSummariesForUser(user.id).associate { it.session.sessionId to it.startedBy?.id }

        assertEquals(mapOf(mine to user.id, theirs to triage.id), startedBy)
    }

    @Test
    fun `a session opened by a message with no author is started by whoever first wrote in it`() {
        val greeted = UUIDv7.generateString()
        val unattended = UUIDv7.generateString()
        chatSessionRepository.createSessionWithMessage(greeted, user, null, MessageWrite(message("welcome", MessageRole.SYSTEM)))
        chatSessionRepository.addMessage(greeted, MessageWrite(message("hi"), user))
        chatSessionRepository.createSessionWithMessage(unattended, user, null, MessageWrite(message("welcome", MessageRole.SYSTEM)))

        val startedBy = chatSessionRepository.listSessionSummariesForUser(user.id).associate { it.session.sessionId to it.startedBy?.id }

        assertEquals(mapOf(greeted to user.id, unattended to null), startedBy)
    }

    @Test
    fun `a session's first message can be sent to several recipients and carry data`() {
        val sessionId = UUIDv7.generateString()
        val triage = TestSessionUser(UUID.randomUUID().toString(), "Triage").also { graphObjectManager.save(it) }
        val record = DataAttachmentData.of(type = "entity", title = "Customer Northwind", json = """{"id":"c-1"}""")

        chatSessionRepository.createSessionWithMessage(
            sessionId, user, "Opened to two",
            MessageWrite(message("look"), author = user, recipients = listOf(triage, user), dataAttachments = listOf(record)),
        )

        val reloaded = chatSessionRepository.getMessages(sessionId).single()
        assertEquals(setOf(triage.id, user.id), reloaded.recipients.map { it.id }.toSet())
        assertEquals(listOf(record.json), reloaded.dataAttachments.map { it.json })
    }

    @Test
    fun `sessions are paged by activity`() {
        val ids = (1..3).map { UUIDv7.generateString() }
        ids.forEachIndexed { i, id ->
            chatSessionRepository.createSession(id, user, "S$i")
            repositoryAt(Instant.parse("2040-01-0${i + 1}T00:00:00Z")).addMessage(id, message("m$i"))
        }

        val firstPage = chatSessionRepository.listSessionsForUser(
            user.id, SessionPageRequest(pageSize = 2, order = SessionOrder.LAST_ACTIVITY),
        )
        val secondPage = chatSessionRepository.listSessionsForUser(
            user.id, SessionPageRequest(pageSize = 2, cursor = firstPage.nextCursor, order = SessionOrder.LAST_ACTIVITY),
        )

        assertEquals(ids.reversed(), (firstPage.items + secondPage.items).map { it.session.sessionId })
        assertNull(secondPage.nextCursor)
    }

    @Test
    fun `a re-embed rewrites every message not at the model, a long one among them, and a rerun does nothing`() {
        val sessionId = UUIDv7.generateString()
        chatSessionRepository.createSession(sessionId, user, "Re-embed")
        // Over the 4,000 characters Oracle reads a text as in a predicate.
        listOf("short", "long ".repeat(1000), "").forEach { chatSessionRepository.addMessage(sessionId, message(it)) }

        val report = chatSessionRepository.reembedMessages("narrow", spec(), constantVectors())

        assertEquals(2, report.messages, "the empty message has nothing to embed")
        val messages = chatSessionRepository.getMessages(sessionId)
        assertEquals(listOf("narrow", "narrow", null), messages.map { it.embeddingModel })
        assertEquals(listOf(WIDTH, WIDTH, null), messages.map { it.embedding?.size })
        assertEquals(0, chatSessionRepository.reembedMessages("narrow", spec()) { error("should not embed") }.messages)
    }

    @Test
    fun `a re-embed names the message the service refuses, writes the rest, and a rerun finishes`() {
        val sessionId = UUIDv7.generateString()
        chatSessionRepository.createSession(sessionId, user, "Re-embed")
        val poison = message("poison")
        listOf(message("one"), poison, message("two")).forEach { chatSessionRepository.addMessage(sessionId, it) }

        val failure = assertThrows(MessageReembedIncompleteException::class.java) {
            chatSessionRepository.reembedMessages("narrow", spec()) { texts ->
                check("poison" !in texts) { "refused" }
                texts.map { List(WIDTH) { 0.5 } }
            }
        }

        assertEquals(listOf(poison.messageId), failure.failedMessageIds)
        assertEquals(2, failure.report.messages)
        assertEquals(1, chatSessionRepository.reembedMessages("narrow", spec(), constantVectors()).messages)
    }

    @Test
    fun `a dead embedding service is abandoned, and what was not attempted is counted`() {
        val sessionId = UUIDv7.generateString()
        chatSessionRepository.createSession(sessionId, user, "Re-embed")
        repeat(MESSAGES) { chatSessionRepository.addMessage(sessionId, message("m$it")) }

        val failure = assertThrows(MessageReembedIncompleteException::class.java) {
            chatSessionRepository.reembedMessages("narrow", spec()) { error("down") }
        }

        assertTrue(failure.abandoned)
        assertTrue(failure.notAttempted > 0)
        assertEquals(MESSAGES, failure.failedMessageIds.size + failure.notAttempted)
        assertEquals(0, failure.report.messages)
    }

    private fun message(content: String, role: MessageRole = MessageRole.USER) =
        MessageData(UUIDv7.generateString(), role, content, Instant.now())

    private fun repositoryAt(instant: Instant) = ChatSessionRepositoryImpl(
        graphObjectManager,
        persistenceManager,
        clock = Clock.fixed(instant, ZoneOffset.UTC),
    )

    private fun spec() = VectorIndexSpec(
        label = "StoredMessage",
        property = "embedding",
        dimensions = WIDTH,
        similarity = SimilarityFunction.COSINE,
        name = "oracle_message_embedding",
    )

    private fun constantVectors(): (List<String>) -> List<List<Double>> = { texts -> texts.map { List(WIDTH) { 0.5 } } }

    private companion object {
        const val WIDTH = 4

        /** Enough that failing one by one passes the limit at which a run gives up. */
        const val MESSAGES = 16
    }
}
