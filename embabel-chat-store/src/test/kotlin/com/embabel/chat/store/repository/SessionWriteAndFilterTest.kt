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
import com.embabel.chat.store.model.SessionRef
import com.embabel.chat.store.model.SessionSummary
import com.embabel.chat.store.model.StoredSession
import com.embabel.chat.store.model.StoredUser
import com.embabel.chat.store.model.TestSessionUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.time.Instant

class SessionWriteAndFilterTest {

    private val owner = TestSessionUser("owner", "Owner")

    @Test
    fun `a filter takes top-level sessions of any kind unless told otherwise`() {
        val chat = data("chat")
        val thread = data("thread")

        assertTrue(SessionFilter.TOP_LEVEL.matches(chat, partOf = null))
        assertTrue(SessionFilter.TOP_LEVEL.matches(thread, partOf = null))
        assertFalse(SessionFilter.TOP_LEVEL.matches(chat, partOf = "a-thread"))
        assertTrue(SessionFilter(includeParts = true).matches(chat, partOf = "a-thread"))
        assertTrue(SessionFilter(kind = "thread").matches(thread, partOf = null))
        assertFalse(SessionFilter(kind = "thread").matches(chat, partOf = null))
    }

    @Test
    fun `a blank kind or session ID is refused`() {
        assertThrows(IllegalArgumentException::class.java) { SessionWrite(kind = " ") }
        assertThrows(IllegalArgumentException::class.java) { SessionWrite(partOf = "") }
        assertThrows(IllegalArgumentException::class.java) { SessionFilter(kind = "") }
        assertEquals(SessionWrite.DEFAULT, SessionWrite(kind = SessionData.DEFAULT_KIND))
    }

    @Test
    fun `a repository that predates kinds takes the default session and refuses anything more`() {
        val created = StoredSession(data("chat"), owner)
        val write = MessageWrite(MessageData("m", MessageRole.USER, "hi", Instant.EPOCH))
        val delegate = mock<ChatSessionRepository> {
            on { createSession(eq("s"), eq(owner), anyOrNull()) } doReturn created
            on { createSessionWithMessage(eq("s"), eq(owner), anyOrNull(), eq(write)) } doReturn created
        }
        val repository = legacy(delegate)

        assertSame(created, repository.createSession("s", owner, null, SessionWrite.DEFAULT))
        verify(delegate).createSession("s", owner, null)
        assertThrows(IllegalArgumentException::class.java) {
            repository.createSession("s", owner, null, SessionWrite(kind = "thread"))
        }
        assertSame(created, repository.createSessionWithMessage("s", owner, null, write, SessionWrite.DEFAULT))
        verify(delegate).createSessionWithMessage("s", owner, null, write)
        assertThrows(IllegalArgumentException::class.java) {
            repository.createSessionWithMessage("s", owner, null, write, SessionWrite(partOf = "t"))
        }
        assertTrue(repository.listParts("t").isEmpty())
        assertThrows(UnsupportedOperationException::class.java) { repository.findOrCreatePart("t", owner, "chat", null) }
        assertFalse(repository.findPart("t", owner.id).isPresent)
        assertEquals(0, repository.backfillSessionKinds())
    }

    @Test
    fun `a repository that predates kinds filters its listing in memory`() {
        val chat = StoredSession(data("chat", "a"), owner)
        val thread = StoredSession(data("thread", "b"), owner)
        val side = StoredSession(data("chat", "c"), owner, partOf = SessionRef("b"))
        val repository = legacy(mock<ChatSessionRepository> {
            on { listSessionsForUser(eq("owner"), eq(SessionOrder.CREATED)) } doReturn listOf(side, thread, chat)
        })

        assertEquals(listOf(thread, chat), repository.listSessionsForUser("owner", SessionOrder.CREATED, SessionFilter.TOP_LEVEL))
        assertEquals(listOf(side, chat), repository.listSessionsForUser("owner", SessionOrder.CREATED, SessionFilter(kind = "chat", includeParts = true)))
        assertEquals(listOf(thread), repository.listSessionsForUser("owner", SessionPageRequest(filter = SessionFilter(kind = "thread"))).items)
    }

    @Test
    fun `a repository that predates kinds filters and counts its summaries in memory`() {
        val chat = SessionSummary(data("chat", "a"), owner, messageCount = 0)
        val thread = SessionSummary(data("thread", "b"), owner, messageCount = 0)
        val side = SessionSummary(data("chat", "c"), owner, messageCount = 0, partOf = SessionRef("b"))
        val repository = legacy(mock<ChatSessionRepository> {
            on { listSessionSummariesForUser(eq("owner"), eq(SessionOrder.CREATED)) } doReturn listOf(side, thread, chat)
        })

        assertEquals(listOf(thread, chat), repository.listSessionSummariesForUser("owner", SessionOrder.CREATED, SessionFilter.TOP_LEVEL))
        assertEquals(
            listOf(chat),
            repository.listSessionSummariesForUser("owner", SessionPageRequest(filter = SessionFilter(kind = "chat"))).items,
        )
        assertEquals(2, repository.countSessionsForUser("owner", SessionFilter(kind = "chat", includeParts = true)))
    }

    /**
     * A repository written before kinds existed: it implements what every implementation had then,
     * through [delegate], and leaves the methods added with kinds to the interface defaults.
     */
    private fun legacy(delegate: ChatSessionRepository) = object : ChatSessionRepository by delegate {
        override fun createSession(sessionId: String, owner: StoredUser, title: String?, session: SessionWrite) =
            super<ChatSessionRepository>.createSession(sessionId, owner, title, session)

        override fun createSessionWithMessage(sessionId: String, owner: StoredUser, title: String?, write: MessageWrite, session: SessionWrite) =
            super<ChatSessionRepository>.createSessionWithMessage(sessionId, owner, title, write, session)

        override fun listParts(sessionId: String) = super<ChatSessionRepository>.listParts(sessionId)

        override fun listSessionsForUser(userId: String, page: SessionPageRequest) =
            super<ChatSessionRepository>.listSessionsForUser(userId, page)

        override fun findPart(partOf: String, withUserId: String) =
            super<ChatSessionRepository>.findPart(partOf, withUserId)

        override fun findOrCreatePart(partOf: String, heldWith: StoredUser, kind: String, title: String?) =
            super<ChatSessionRepository>.findOrCreatePart(partOf, heldWith, kind, title)

        override fun backfillSessionKinds() = super<ChatSessionRepository>.backfillSessionKinds()

        override fun listSessionsForUser(userId: String, order: SessionOrder, filter: SessionFilter) =
            super<ChatSessionRepository>.listSessionsForUser(userId, order, filter)

        override fun listSessionSummariesForUser(userId: String, order: SessionOrder, filter: SessionFilter) =
            super<ChatSessionRepository>.listSessionSummariesForUser(userId, order, filter)

        override fun listSessionSummariesForUser(userId: String, page: SessionPageRequest) =
            super<ChatSessionRepository>.listSessionSummariesForUser(userId, page)

        override fun countSessionsForUser(userId: String, filter: SessionFilter) =
            super<ChatSessionRepository>.countSessionsForUser(userId, filter)
    }

    private fun data(kind: String, id: String = "s") =
        SessionData(sessionId = id, title = null, createdAt = Instant.EPOCH, kind = kind)
}
