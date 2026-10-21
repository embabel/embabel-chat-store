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
import com.embabel.chat.store.model.MessageData
import com.embabel.chat.store.model.SessionData
import com.embabel.chat.store.model.TestSessionUser
import com.embabel.chat.store.util.UUIDv7
import org.drivine.manager.PersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.manager.load
import org.drivine.query.QuerySpecification
import org.drivine.query.transform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID

/**
 * What a session is — its kind, the session it is part of, and who it is held with
 * (embabel/embabel-chat-store#45) — on every store the repository runs on. Each store's subclass
 * says how to reach its graph below the object manager, for fixtures and for checks.
 */
abstract class SessionKindsAndPartsTests {

    @Autowired
    protected lateinit var chatSessionRepository: ChatSessionRepository

    @Autowired
    protected lateinit var graphObjectManager: StatelessGraphObjectManager

    @Autowired
    protected lateinit var persistenceManager: PersistenceManager

    /** Runs Cypher fixtures and checks against the store under test. */
    protected abstract val fixtures: PersistenceManager

    /** Leaves the store as the test needs it before each test. */
    protected open fun prepare() {}

    private lateinit var owner: TestSessionUser
    private lateinit var agent: TestSessionUser

    @BeforeEach
    fun setUpUsers() {
        prepare()
        owner = TestSessionUser(UUID.randomUUID().toString(), "Owner")
        agent = TestSessionUser(UUID.randomUUID().toString(), "Agent")
        graphObjectManager.save(owner)
        graphObjectManager.save(agent)
    }

    @Test
    fun `a session is a chat unless created as another kind, and is listed by kind`() {
        val chat = UUIDv7.generateString()
        val thread = UUIDv7.generateString()
        chatSessionRepository.createSession(chat, owner, "Chat")
        chatSessionRepository.createSession(thread, owner, "Thread", SessionWrite(kind = "thread"))

        assertEquals(SessionData.DEFAULT_KIND, chatSessionRepository.findBySessionId(chat).get().session.kind)
        assertEquals("thread", chatSessionRepository.findBySessionId(thread).get().session.kind)
        assertEquals(listOf(thread, chat), ids(chatSessionRepository.listSessionsForUser(owner.id).map { it.session }))
        assertEquals(
            listOf(thread),
            ids(chatSessionRepository.listSessionsForUser(owner.id, SessionOrder.CREATED, SessionFilter(kind = "thread")).map { it.session }),
        )
        assertEquals(
            listOf(chat),
            ids(chatSessionRepository.listSessionSummariesForUser(owner.id, SessionOrder.CREATED, SessionFilter(kind = "chat")).map { it.session }),
        )
        assertEquals(1, chatSessionRepository.countSessionsForUser(owner.id, SessionFilter(kind = "thread")))
        assertEquals(
            listOf(thread),
            ids(chatSessionRepository.listSessionsForUser(
                owner.id, SessionPageRequest(filter = SessionFilter(kind = "thread")),
            ).items.map { it.session }),
        )
    }

    @Test
    fun `a session stored before kinds existed is a chat, and the backfill writes it so`() {
        val old = UUIDv7.generateString()
        val thread = UUIDv7.generateString()
        chatSessionRepository.createSession(old, owner, "Old")
        chatSessionRepository.createSession(thread, owner, "Thread", SessionWrite(kind = "thread"))
        fixtures.execute(
            QuerySpecification.withStatement("MATCH (s:ChatSession {sessionId: \$id}) REMOVE s.kind")
                .bind(mapOf("id" to old))
        )
        assertNull(storedKind(old))

        assertEquals(SessionData.DEFAULT_KIND, chatSessionRepository.findBySessionId(old).get().session.kind)
        assertEquals(
            listOf(old),
            ids(chatSessionRepository.listSessionsForUser(owner.id, SessionOrder.CREATED, SessionFilter(kind = "chat")).map { it.session }),
        )
        assertEquals(1, chatSessionRepository.countSessionsForUser(owner.id, SessionFilter(kind = "chat")))

        assertTrue(chatSessionRepository.backfillSessionKinds() >= 1)
        assertEquals(SessionData.DEFAULT_KIND, storedKind(old))
        assertEquals("thread", storedKind(thread))
        assertEquals(0, chatSessionRepository.backfillSessionKinds())
    }

    @Test
    fun `a page walk keeps to its filter past the first page, in either order`() {
        val old = UUIDv7.generateString()
        val chat = UUIDv7.generateString()
        val thread = UUIDv7.generateString()
        val side = UUIDv7.generateString()
        chatSessionRepository.createSession(old, owner, "Old")
        chatSessionRepository.createSession(chat, owner, "Chat")
        chatSessionRepository.createSession(thread, owner, "Thread", SessionWrite(kind = "thread"))
        chatSessionRepository.createSession(side, owner, null, SessionWrite(partOf = thread, heldWith = agent))
        fixtures.execute(
            QuerySpecification.withStatement("MATCH (s:ChatSession {sessionId: \$id}) REMOVE s.kind")
                .bind(mapOf("id" to old))
        )

        fun <T> walk(page: (SessionPageRequest) -> SessionPage<T>, id: (T) -> String, request: (String?) -> SessionPageRequest): List<String> {
            val seen = mutableListOf<String>()
            var cursor: String? = null
            do {
                val result = page(request(cursor))
                seen += result.items.map(id)
                cursor = result.nextCursor
            } while (cursor != null)
            return seen
        }

        for (order in SessionOrder.entries) {
            for ((filter, expected) in listOf(
                SessionFilter(kind = SessionData.DEFAULT_KIND) to listOf(chat, old),
                SessionFilter(includeParts = true) to listOf(side, thread, chat, old),
            )) {
                val request = { cursor: String? -> SessionPageRequest(pageSize = 1, cursor = cursor, order = order, filter = filter) }
                assertEquals(expected, walk({ chatSessionRepository.listSessionsForUser(owner.id, it) }, { it.session.sessionId }, request), "$order $filter")
                assertEquals(expected, walk({ chatSessionRepository.listSessionSummariesForUser(owner.id, it) }, { it.session.sessionId }, request), "$order $filter")
            }
        }
    }

    @Test
    fun `a side session is part of a thread and held with an agent, and owner listings leave it out`() {
        val thread = UUIDv7.generateString()
        val side = UUIDv7.generateString()
        chatSessionRepository.createSession(thread, owner, "Thread", SessionWrite(kind = "thread"))
        val threadBefore = graphObjectManager.load<SessionData>(thread)!!

        val created = chatSessionRepository.createSession(side, owner, null, SessionWrite(partOf = thread, heldWith = agent))

        assertEquals(thread, created.partOf?.sessionId)
        assertEquals(agent.id, created.heldWith?.id)
        val found = chatSessionRepository.findBySessionId(side).get()
        assertEquals(thread, found.partOf?.sessionId)
        assertEquals(agent.id, found.heldWith?.id)
        assertNull(chatSessionRepository.findBySessionId(thread).get().partOf)
        // The thread gains a relationship, which the second token of its stamp speaks for; its
        // properties, and the token a save of them is checked against, are as they were.
        val threadAfter = graphObjectManager.load<SessionData>(thread)!!
        assertEquals(threadBefore.copy(stamp = null), threadAfter.copy(stamp = null))
        assertEquals(threadBefore.stamp!!.substringBefore(':'), threadAfter.stamp!!.substringBefore(':'))

        assertEquals(listOf(thread), ids(chatSessionRepository.listSessionsForUser(owner.id).map { it.session }))
        assertEquals(listOf(thread), ids(chatSessionRepository.listSessionSummariesForUser(owner.id).map { it.session }))
        assertEquals(listOf(thread), ids(chatSessionRepository.listSessionsForUser(owner.id, SessionPageRequest()).items.map { it.session }))
        assertEquals(listOf(thread), ids(chatSessionRepository.listSessionSummariesForUser(owner.id, SessionPageRequest()).items.map { it.session }))
        assertEquals(1, chatSessionRepository.countSessionsForUser(owner.id))

        val everything = SessionFilter(includeParts = true)
        assertEquals(listOf(side, thread), ids(chatSessionRepository.listSessionsForUser(owner.id, SessionOrder.CREATED, everything).map { it.session }))
        val summaries = chatSessionRepository.listSessionSummariesForUser(owner.id, SessionOrder.CREATED, everything)
        assertEquals(thread, summaries.first().partOf?.sessionId)
        assertEquals(agent.id, summaries.first().heldWith?.id)
        assertEquals(2, chatSessionRepository.countSessionsForUser(owner.id, everything))
    }

    @Test
    fun `a session is part of a top-level session of the same owner, and of nothing else`() {
        val stranger = TestSessionUser(UUID.randomUUID().toString(), "Stranger")
        graphObjectManager.save(stranger)
        val thread = UUIDv7.generateString()
        val side = UUIDv7.generateString()
        chatSessionRepository.createSession(thread, owner, "Thread")
        chatSessionRepository.createSession(side, owner, null, SessionWrite(partOf = thread))

        fun refused(sessionId: String, write: SessionWrite, by: TestSessionUser = owner) {
            assertThrows(IllegalArgumentException::class.java) {
                chatSessionRepository.createSession(sessionId, by, null, write)
            }
            assertFalse(chatSessionRepository.findBySessionId(sessionId).isPresent, "nothing is made when refused")
        }
        refused(UUIDv7.generateString(), SessionWrite(partOf = UUIDv7.generateString()))
        refused(UUIDv7.generateString(), SessionWrite(partOf = side))
        refused(UUIDv7.generateString(), SessionWrite(partOf = thread), by = stranger)
        val itself = UUIDv7.generateString()
        refused(itself, SessionWrite(partOf = itself))
        assertThrows(IllegalArgumentException::class.java) {
            chatSessionRepository.createSessionWithMessage(
                UUIDv7.generateString(), owner, null, MessageWrite(message("hi")), SessionWrite(partOf = side),
            )
        }
    }

    @Test
    fun `an agent's side session in a thread is found again, made once, and the earliest wins`() {
        val thread = UUIDv7.generateString()
        chatSessionRepository.createSession(thread, owner, "Thread", SessionWrite(kind = "thread"))
        assertFalse(chatSessionRepository.findPart(thread, agent.id).isPresent)

        val made = chatSessionRepository.findOrCreatePart(thread, agent)
        val again = chatSessionRepository.findOrCreatePart(thread, agent)

        assertEquals(made.session.sessionId, again.session.sessionId)
        assertEquals(owner.id, made.owner.id, "a side session is owned by the thread's owner")
        assertEquals(SessionData.DEFAULT_KIND, made.session.kind)
        assertEquals(made.session.sessionId, chatSessionRepository.findPart(thread, agent.id).get().session.sessionId)
        assertFalse(chatSessionRepository.findPart(thread, owner.id).isPresent)

        // Two callers at once can make two; every lookup settles on the earliest.
        val later = UUIDv7.generateString()
        chatSessionRepository.createSession(later, owner, null, SessionWrite(partOf = thread, heldWith = agent))
        assertEquals(made.session.sessionId, chatSessionRepository.findPart(thread, agent.id).get().session.sessionId)

        val parts = chatSessionRepository.listParts(thread)
        assertEquals(listOf(later, made.session.sessionId), parts.map { it.session.sessionId })
        assertTrue(parts.all { it.heldWith?.id == agent.id && it.partOf?.sessionId == thread })
        assertTrue(chatSessionRepository.listParts(made.session.sessionId).isEmpty())

        val reviewer = TestSessionUser(UUID.randomUUID().toString(), "Reviewer").also { graphObjectManager.save(it) }
        val review = chatSessionRepository.findOrCreatePart(thread, reviewer, kind = "review", title = "Review")
        assertEquals("review", chatSessionRepository.findBySessionId(review.session.sessionId).get().session.kind)
        assertEquals("Review", review.session.title)
        assertEquals(made.session.sessionId, chatSessionRepository.findOrCreatePart(thread, agent, kind = "review").session.sessionId)

        assertThrows(IllegalArgumentException::class.java) {
            chatSessionRepository.findOrCreatePart(UUIDv7.generateString(), agent)
        }
        assertThrows(IllegalArgumentException::class.java, { chatSessionRepository.findOrCreatePart(later, agent) }, "a part has no parts")
        assertTrue(chatSessionRepository.listParts(later).isEmpty())
    }

    @Test
    fun `deleting a thread deletes its side sessions and their messages, and keeps every user`() {
        val thread = UUIDv7.generateString()
        val side = UUIDv7.generateString()
        val threadMessage = message("in the thread")
        val sideMessage = message("to the agent")
        chatSessionRepository.createSessionWithMessage(thread, owner, "Thread", MessageWrite(threadMessage, author = owner), SessionWrite(kind = "thread"))
        chatSessionRepository.createSessionWithMessage(
            side, owner, null, MessageWrite(sideMessage, author = agent, recipients = listOf(owner)), SessionWrite(partOf = thread, heldWith = agent),
        )

        chatSessionRepository.deleteSession(side)
        assertFalse(chatSessionRepository.findBySessionId(side).isPresent)
        assertEquals(0, count("StoredMessage", "messageId", sideMessage.messageId))
        assertTrue(chatSessionRepository.findBySessionId(thread).isPresent, "deleting a side session keeps its thread")

        val again = chatSessionRepository.findOrCreatePart(thread, agent)
        chatSessionRepository.addMessage(again.session.sessionId, MessageWrite(message("again"), author = agent))
        chatSessionRepository.deleteSession(thread)

        assertFalse(chatSessionRepository.findBySessionId(thread).isPresent)
        assertFalse(chatSessionRepository.findBySessionId(again.session.sessionId).isPresent)
        assertEquals(0, count("StoredMessage", "messageId", threadMessage.messageId))
        assertEquals(0, chatSessionRepository.getMessages(again.session.sessionId).size)
        assertEquals(1, count("User", "id", owner.id))
        assertEquals(1, count("User", "id", agent.id))
    }

    @Test
    fun `a delete that would leave a session nested deeper behind is refused, and deletes nothing`() {
        val thread = UUIDv7.generateString()
        val side = UUIDv7.generateString()
        val deeper = UUIDv7.generateString()
        chatSessionRepository.createSession(thread, owner, "Thread")
        chatSessionRepository.createSession(side, owner, null, SessionWrite(partOf = thread))
        chatSessionRepository.createSession(deeper, owner, null)
        // Only a write below the repository can nest a session two deep.
        fixtures.execute(
            QuerySpecification.withStatement(
                "MATCH (d:ChatSession {sessionId: \$deeper}), (s:ChatSession {sessionId: \$side}) CREATE (d)-[:PART_OF]->(s)"
            ).bind(mapOf("deeper" to deeper, "side" to side))
        )

        assertThrows(IllegalStateException::class.java) { chatSessionRepository.deleteSession(thread) }

        assertEquals(1, count("ChatSession", "sessionId", thread))
        assertEquals(1, count("ChatSession", "sessionId", side))
        assertEquals(1, count("ChatSession", "sessionId", deeper))
    }

    private fun ids(sessions: List<SessionData>) = sessions.map { it.sessionId }

    private fun message(content: String) =
        MessageData(UUIDv7.generateString(), MessageRole.USER, content, Instant.now())

    private fun storedKind(sessionId: String): String? =
        fixtures.query(
            QuerySpecification.withStatement("MATCH (s:ChatSession {sessionId: \$id}) RETURN s.kind AS kind")
                .bind(mapOf("id" to sessionId))
                .transform<String>()
        ).singleOrNull()

    /** The id is bound; only the fixed label and property names are written into the statement. */
    private fun count(label: String, idProperty: String, id: String): Int =
        fixtures.getOne(
            QuerySpecification.withStatement("MATCH (n:$label {$idProperty: \$id}) RETURN count(n) AS count")
                .bind(mapOf("id" to id))
                .transform(Int::class.java)
        )
}
