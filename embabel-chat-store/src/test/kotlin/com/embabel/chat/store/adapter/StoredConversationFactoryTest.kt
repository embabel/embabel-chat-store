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
package com.embabel.chat.store.adapter

import com.embabel.agent.api.identity.User
import com.embabel.chat.UserMessage
import com.embabel.chat.event.MessageEvent
import com.embabel.chat.event.MessageStatus
import com.embabel.chat.store.event.SessionEventAwaiter
import com.embabel.chat.store.model.MessageData
import com.embabel.chat.store.model.SessionData
import com.embabel.chat.store.model.SimpleStoredUser
import com.embabel.chat.store.model.SimpleStoredMessage
import com.embabel.chat.store.model.StoredSession
import com.embabel.chat.store.repository.ChatSessionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import java.time.Instant
import java.util.Optional
import java.util.concurrent.atomic.AtomicBoolean

class StoredConversationFactoryTest {

    private val repository: ChatSessionRepository = mock()
    private val factory = StoredConversationFactory(repository, SessionEventAwaiter())
    private val owner = SimpleStoredUser("owner", "Owner", "owner", null)

    @Test
    fun `createForParticipants creates the backing session`() {
        whenever(repository.findBySessionId("new-session")).thenReturn(Optional.empty())
        whenever(repository.createSession("new-session", owner, "New title"))
            .thenReturn(session("new-session", owner, "New title"))

        val conversation = factory.createForParticipants("new-session", owner, null, "New title")

        assertEquals("new-session", conversation.id)
        verify(repository).createSession("new-session", owner, "New title")
    }

    @Test
    fun `first message persists after the factory creates the session`() {
        val sessionCreated = AtomicBoolean(false)
        val agent = SimpleStoredUser("agent", "Agent", "agent", null)
        val publisher: ApplicationEventPublisher = mock()
        whenever(repository.findBySessionId("first-message")).thenReturn(Optional.empty())
        whenever(repository.createSession("first-message", owner, "Test title")).thenAnswer {
            sessionCreated.set(true)
            session("first-message", owner, "Test title")
        }
        whenever(repository.addMessage(eq("first-message"), any(), eq(owner), eq(agent), any()))
            .thenAnswer { invocation ->
                check(sessionCreated.get()) { "The session must exist before the first message" }
                val message = invocation.getArgument<MessageData>(1)
                session("first-message", owner, "Test title").copy(
                    messages = listOf(SimpleStoredMessage(message, owner, agent))
                )
            }

        val conversation = StoredConversationFactory(repository, SessionEventAwaiter(), publisher)
            .createForParticipants("first-message", owner, agent, "Test title")
        conversation.addMessage(UserMessage("Hello"))

        val events = ArgumentCaptor.forClass(Any::class.java)
        verify(publisher, timeout(5000).atLeast(2)).publishEvent(events.capture())
        assertEquals(
            listOf(MessageStatus.ADDED, MessageStatus.PERSISTED),
            events.allValues.filterIsInstance<MessageEvent>().map { it.status },
        )
    }

    @Test
    fun `createForParticipants reuses a session owned by the user`() {
        whenever(repository.findBySessionId("existing-session"))
            .thenReturn(Optional.of(session("existing-session", owner, "Stored title")))

        val conversation = factory.createForParticipants("existing-session", owner, null, "New title")

        assertEquals("existing-session", conversation.id)
        verify(repository, never()).createSession(any(), any(), anyOrNull())
    }

    @Test
    fun `createForParticipants rejects a session owned by another user`() {
        val otherOwner = SimpleStoredUser("other", "Other", "other", null)
        whenever(repository.findBySessionId("existing-session"))
            .thenReturn(Optional.of(session("existing-session", otherOwner, "Private")))

        val error = assertThrows(IllegalArgumentException::class.java) {
            factory.createForParticipants("existing-session", owner, null, null)
        }

        assertEquals("Session existing-session belongs to a different user", error.message)
        verify(repository, never()).createSession(any(), any(), anyOrNull())
    }

    @Test
    fun `createForParticipants validates the user before accessing storage`() {
        val user: User = mock()

        assertThrows(IllegalArgumentException::class.java) {
            factory.createForParticipants("new-session", user, null, null)
        }

        verifyNoInteractions(repository)
    }

    private fun session(id: String, owner: SimpleStoredUser, title: String?) = StoredSession(
        session = SessionData(sessionId = id, title = title, createdAt = Instant.now()),
        owner = owner,
        messages = emptyList(),
    )
}
