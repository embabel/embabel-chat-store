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

import com.embabel.chat.MessageRole
import com.embabel.chat.UserMessage
import com.embabel.chat.AssistantMessage
import com.embabel.chat.SystemMessage
import com.embabel.chat.event.MessageEvent
import com.embabel.chat.event.MessageStatus
import com.embabel.chat.store.embedding.EmbeddingResult
import com.embabel.chat.store.embedding.MessageEmbedder
import com.embabel.chat.store.event.SessionEventAwaiter
import com.embabel.chat.store.model.MessageData
import com.embabel.chat.store.model.SessionData
import com.embabel.chat.store.model.SimpleStoredMessage
import com.embabel.chat.store.model.StoredSession
import com.embabel.chat.store.model.StoredUser
import com.embabel.chat.store.repository.ChatSessionRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StoredConversationTest {

    private lateinit var repository: ChatSessionRepository
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var sessionEventAwaiter: SessionEventAwaiter
    private lateinit var messageEmbedder: MessageEmbedder
    private lateinit var scope: CoroutineScope

    private val sessionId = "session-1"
    private val user = TestUser("user-1", "Alice")
    private val agent = TestUser("agent-1", "Bot")

    @BeforeEach
    fun setUp() {
        repository = mock()
        eventPublisher = mock()
        sessionEventAwaiter = mock()
        messageEmbedder = mock()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        whenever(sessionEventAwaiter.register(any())).thenReturn(CompletableDeferred())
        runBlocking { whenever(messageEmbedder.embed(any())).thenReturn(null) }
    }

    private fun createConversation(
        title: String? = null,
        titleGenerator: TitleGenerator? = null,
        embedder: MessageEmbedder = messageEmbedder,
    ) = StoredConversation(
        id = sessionId,
        repository = repository,
        sessionEventAwaiter = sessionEventAwaiter,
        messageEmbedder = embedder,
        eventPublisher = eventPublisher,
        user = user,
        agent = agent,
        title = title,
        titleGenerator = titleGenerator,
        scope = scope
    )

    private fun stubAddMessage(vararg messages: SimpleStoredMessage): StoredSession {
        val session = StoredSession(
            session = SessionData(sessionId = sessionId, title = "Test", createdAt = Instant.now()),
            owner = user,
            messages = messages.toList()
        )
        whenever(repository.addMessage(eq(sessionId), any(), any(), any())).thenReturn(session)
        return session
    }

    // ==================== Pending buffer tests ====================

    @Test
    fun `addMessage returns immediately and message is visible in messages via pending buffer`() {
        // Repository addMessage will block on a latch — simulating slow DB
        val dbLatch = CountDownLatch(1)
        val session = StoredSession(
            session = SessionData(sessionId = sessionId, title = "Test", createdAt = Instant.now()),
            owner = user,
            messages = listOf(
                SimpleStoredMessage(MessageData("msg-1", MessageRole.USER, "Hello", Instant.now()))
            )
        )
        whenever(repository.addMessage(eq(sessionId), any(), any(), any())).thenAnswer {
            dbLatch.await(5, TimeUnit.SECONDS)
            session
        }
        whenever(repository.getMessages(sessionId)).thenReturn(emptyList())

        val conversation = createConversation()
        val msg = UserMessage(content = "Hello")

        // addMessage should return immediately (not block on DB)
        conversation.addMessage(msg)

        // Message should be visible via pending buffer even though DB hasn't completed
        val messages = conversation.messages
        assertEquals(1, messages.size)
        assertEquals("Hello", messages[0].content)
        assertEquals(MessageRole.USER, messages[0].role)

        dbLatch.countDown()
    }

    @Test
    fun `messages deduplicates pending and DB results after persistence completes`() {
        val persistedLatch = CountDownLatch(1)

        whenever(repository.addMessage(eq(sessionId), any(), any(), any())).thenAnswer { invocation ->
            val messageData = invocation.getArgument<MessageData>(1)
            val resultSession = StoredSession(
                session = SessionData(sessionId = sessionId, title = "Test", createdAt = Instant.now()),
                owner = user,
                messages = listOf(SimpleStoredMessage(messageData))
            )
            persistedLatch.countDown()
            resultSession
        }

        val conversation = createConversation()
        conversation.addMessage(UserMessage(content = "Hello"))

        // Wait for async persistence
        assertTrue(persistedLatch.await(5, TimeUnit.SECONDS))
        Thread.sleep(50) // Let coroutine finish removing from buffer

        // Capture the messageId that was generated
        val captor = argumentCaptor<MessageData>()
        verify(repository, timeout(5000)).addMessage(eq(sessionId), captor.capture(), any(), any())
        val persistedData = captor.firstValue

        // Now getMessages returns the persisted message
        whenever(repository.getMessages(sessionId)).thenReturn(
            listOf(SimpleStoredMessage(persistedData))
        )

        // Should see exactly 1 message — no duplicates
        val messages = conversation.messages
        assertEquals(1, messages.size)
        assertEquals("Hello", messages[0].content)
    }

    @Test
    fun `message stays in pending buffer on persistence failure`() {
        val failureLatch = CountDownLatch(1)
        whenever(repository.addMessage(eq(sessionId), any(), any(), any()))
            .thenAnswer {
                failureLatch.countDown()
                throw RuntimeException("DB unavailable")
            }
        whenever(repository.getMessages(sessionId)).thenReturn(emptyList())

        val conversation = createConversation()
        conversation.addMessage(UserMessage(content = "Hello"))

        assertTrue(failureLatch.await(5, TimeUnit.SECONDS))
        Thread.sleep(50)

        // Message should still be visible from the pending buffer
        val messages = conversation.messages
        assertEquals(1, messages.size)
        assertEquals("Hello", messages[0].content)
    }

    @Test
    fun `multiple messages maintain order in pending buffer`() {
        val latch = CountDownLatch(1)
        whenever(repository.addMessage(eq(sessionId), any(), any(), any())).thenAnswer {
            latch.await(5, TimeUnit.SECONDS)
            StoredSession(
                session = SessionData(sessionId = sessionId, title = "Test", createdAt = Instant.now()),
                owner = user,
                messages = emptyList()
            )
        }
        whenever(repository.getMessages(sessionId)).thenReturn(emptyList())

        val conversation = createConversation()
        conversation.addMessage(UserMessage(content = "First"))
        conversation.addMessage(AssistantMessage(content = "Second"))
        conversation.addMessage(UserMessage(content = "Third"))

        val messages = conversation.messages
        assertEquals(3, messages.size)
        assertEquals("First", messages[0].content)
        assertEquals("Second", messages[1].content)
        assertEquals("Third", messages[2].content)

        latch.countDown()
    }

    // ==================== Event tests ====================

    @Test
    fun `ADDED event is published synchronously on addMessage`() {
        stubAddMessage()

        val conversation = createConversation()
        conversation.addMessage(UserMessage(content = "Hello"))

        // ADDED event should fire synchronously — verifiable immediately
        val captor = ArgumentCaptor.forClass(MessageEvent::class.java)
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture())

        val addedEvent = captor.allValues.filterIsInstance<MessageEvent>()
            .first { it.status == MessageStatus.ADDED }
        assertEquals(sessionId, addedEvent.conversationId)
        assertEquals("Hello", addedEvent.content)
        assertEquals(MessageRole.USER, addedEvent.role)
        assertEquals("user-1", addedEvent.fromUserId)
        assertEquals("agent-1", addedEvent.toUserId)
    }

    @Test
    fun `PERSISTED event is published after async DB write`() {
        val msgData = MessageData("msg-1", MessageRole.USER, "Hello", Instant.now())
        stubAddMessage(SimpleStoredMessage(msgData))

        val conversation = createConversation()
        conversation.addMessage(UserMessage(content = "Hello"))

        // Wait for async persistence + event
        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(eventPublisher, timeout(5000).atLeast(2)).publishEvent(captor.capture())

        val events = captor.allValues.filterIsInstance<MessageEvent>()
        assertTrue(events.any { it.status == MessageStatus.PERSISTED })
    }

    @Test
    fun `PERSISTENCE_FAILED event is published on DB failure`() {
        whenever(repository.addMessage(eq(sessionId), any(), any(), any()))
            .thenThrow(RuntimeException("Connection refused"))

        val conversation = createConversation()
        conversation.addMessage(UserMessage(content = "Hello"))

        val captor = ArgumentCaptor.forClass(Any::class.java)
        verify(eventPublisher, timeout(5000).atLeast(2)).publishEvent(captor.capture())

        val events = captor.allValues.filterIsInstance<MessageEvent>()
        val failedEvent = events.first { it.status == MessageStatus.PERSISTENCE_FAILED }
        assertEquals(sessionId, failedEvent.conversationId)
        assertEquals("Hello", failedEvent.content)
        assertNotNull(failedEvent.error)
        assertEquals("Connection refused", failedEvent.error!!.message)
    }

    // ==================== Attribution tests ====================

    @Test
    fun `USER message is attributed from user to agent`() {
        stubAddMessage()

        val conversation = createConversation()
        conversation.addMessage(UserMessage(content = "Hi"))

        val captor = ArgumentCaptor.forClass(MessageEvent::class.java)
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture())

        val added = captor.allValues.filterIsInstance<MessageEvent>()
            .first { it.status == MessageStatus.ADDED }
        assertEquals("user-1", added.fromUserId)
        assertEquals("agent-1", added.toUserId)
    }

    @Test
    fun `ASSISTANT message is attributed from agent to user`() {
        stubAddMessage()

        val conversation = createConversation()
        conversation.addMessage(AssistantMessage(content = "Hello!"))

        val captor = ArgumentCaptor.forClass(MessageEvent::class.java)
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture())

        val added = captor.allValues.filterIsInstance<MessageEvent>()
            .first { it.status == MessageStatus.ADDED }
        assertEquals("agent-1", added.fromUserId)
        assertEquals("user-1", added.toUserId)
    }

    @Test
    fun `SYSTEM message is attributed from null to user`() {
        stubAddMessage()

        val conversation = createConversation()
        conversation.addMessage(SystemMessage(content = "Welcome"))

        val captor = ArgumentCaptor.forClass(MessageEvent::class.java)
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture())

        val added = captor.allValues.filterIsInstance<MessageEvent>()
            .first { it.status == MessageStatus.ADDED }
        assertNull(added.fromUserId)
        assertEquals("user-1", added.toUserId)
    }

    // ==================== Title tests ====================

    @Test
    fun `interim title is set from first user message`() {
        stubAddMessage()

        val conversation = createConversation()
        conversation.addMessage(UserMessage(content = "What is the weather today?"))

        verify(repository).updateSessionTitle(sessionId, "What is the weather today?")
    }

    @Test
    fun `interim title is not set for assistant messages`() {
        stubAddMessage()

        val conversation = createConversation()
        conversation.addMessage(AssistantMessage(content = "Hello there"))

        verify(repository, never()).updateSessionTitle(any(), any())
    }

    @Test
    fun `interim title is truncated for long messages`() {
        stubAddMessage()

        val longContent = "A".repeat(200)
        val conversation = createConversation()
        conversation.addMessage(UserMessage(content = longContent))

        val captor = argumentCaptor<String>()
        verify(repository).updateSessionTitle(eq(sessionId), captor.capture())
        assertTrue(captor.firstValue.length <= TitleGenerator.DEFAULT_MAX_LENGTH)
        assertTrue(captor.firstValue.endsWith("..."))
    }

    // ==================== Misc tests ====================

    @Test
    fun `persistent returns true`() {
        val conversation = createConversation()
        assertTrue(conversation.persistent())
    }

    @Test
    fun `last returns read-only snapshot`() {
        whenever(repository.getMessages(sessionId)).thenReturn(
            listOf(
                SimpleStoredMessage(MessageData("m1", MessageRole.USER, "One", Instant.now())),
                SimpleStoredMessage(MessageData("m2", MessageRole.ASSISTANT, "Two", Instant.now())),
                SimpleStoredMessage(MessageData("m3", MessageRole.USER, "Three", Instant.now()))
            )
        )

        val conversation = createConversation()
        val snapshot = conversation.last(2)

        assertEquals(2, snapshot.messages.size)
        assertEquals("Two", snapshot.messages[0].content)
        assertEquals("Three", snapshot.messages[1].content)
        assertFalse(snapshot.persistent())
        assertThrows(UnsupportedOperationException::class.java) {
            snapshot.addMessage(UserMessage(content = "fail"))
        }
    }

    @Test
    fun `truncateForTitle handles multiline and extra whitespace`() {
        val result = StoredConversation.truncateForTitle("  Hello\n  World  \n\n  Foo  ", 50)
        assertEquals("Hello World Foo", result)
    }

    // ==================== Embedding tests ====================

    @Test
    fun `embedder is invoked and embedding is included in persisted MessageData`() {
        val vector = floatArrayOf(0.1f, 0.2f, 0.3f)
        runBlocking {
            whenever(messageEmbedder.embed(any()))
                .thenReturn(EmbeddingResult(vector, "test-model"))
        }
        stubAddMessage()

        val conversation = createConversation()
        conversation.addMessage(UserMessage(content = "Hello"))

        val captor = argumentCaptor<MessageData>()
        verify(repository, timeout(5000)).addMessage(eq(sessionId), captor.capture(), any(), any())
        assertArrayEquals(vector, captor.firstValue.embedding)
        assertEquals("test-model", captor.firstValue.embeddingModel)
    }

    @Test
    fun `null embedding result still persists the message with null embedding`() {
        runBlocking { whenever(messageEmbedder.embed(any())).thenReturn(null) }
        stubAddMessage()

        val conversation = createConversation()
        conversation.addMessage(UserMessage(content = "Hello"))

        val captor = argumentCaptor<MessageData>()
        verify(repository, timeout(5000)).addMessage(eq(sessionId), captor.capture(), any(), any())
        assertNull(captor.firstValue.embedding)
        assertNull(captor.firstValue.embeddingModel)
    }

    @Test
    fun `no embedder configured persists message with null embedding`() {
        stubAddMessage()

        val conversation = StoredConversation(
            id = sessionId,
            repository = repository,
            sessionEventAwaiter = sessionEventAwaiter,
            messageEmbedder = null,
            eventPublisher = eventPublisher,
            user = user,
            agent = agent,
            scope = scope,
        )
        conversation.addMessage(UserMessage(content = "Hello"))

        val captor = argumentCaptor<MessageData>()
        verify(repository, timeout(5000)).addMessage(eq(sessionId), captor.capture(), any(), any())
        assertNull(captor.firstValue.embedding)
        assertNull(captor.firstValue.embeddingModel)
    }

    @Test
    fun `embedder failure does not block persistence`() {
        runBlocking {
            whenever(messageEmbedder.embed(any()))
                .thenThrow(RuntimeException("embedding endpoint unreachable"))
        }
        stubAddMessage()

        val conversation = createConversation()
        conversation.addMessage(UserMessage(content = "Hello"))

        val captor = argumentCaptor<MessageData>()
        verify(repository, timeout(5000)).addMessage(eq(sessionId), captor.capture(), any(), any())
        assertEquals("Hello", captor.firstValue.content)
        assertNull(captor.firstValue.embedding)
        assertNull(captor.firstValue.embeddingModel)
    }
}

private data class TestUser(
    override val id: String,
    override val displayName: String,
    override val username: String = displayName,
    override val email: String? = null
) : StoredUser
