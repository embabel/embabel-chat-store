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
import com.embabel.chat.AssetTracker
import com.embabel.chat.Conversation
import com.embabel.chat.Message
import com.embabel.chat.MessageRole
import com.embabel.chat.event.MessageEvent
import com.embabel.chat.store.event.SessionEventAwaiter
import com.embabel.chat.store.model.MessageData
import com.embabel.chat.store.model.StoredSession
import com.embabel.chat.store.model.StoredUser
import com.embabel.chat.store.repository.ChatSessionRepository
import com.embabel.chat.support.InMemoryAssetTracker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import com.embabel.chat.store.util.UUIDv7
import org.springframework.context.ApplicationEventPublisher

/**
 * A [Conversation] implementation that persists messages to Neo4j via [ChatSessionRepository].
 *
 * This adapter provides persistence for conversations. Messages added via [addMessage]
 * are persisted **synchronously** (the DB write blocks until complete), while post-persistence
 * tasks (title generation, PERSISTED event) run asynchronously:
 *
 * 1. `addMessage()` persists the message to the DB (blocking)
 * 2. On success: title generation and [MessageEvent] with status PERSISTED are published asynchronously
 * 3. On failure: [MessageEvent] with status PERSISTENCE_FAILED is published and the exception is rethrown
 *
 * ## Message Attribution
 *
 * Messages are attributed with `from` (author) and `to` (recipient) based on role:
 * - USER messages: from=[user], to=[agent]
 * - ASSISTANT messages: from=[agent], to=[user]
 * - SYSTEM messages: from=null, to=[user]
 *
 * ## Auto Title Generation
 *
 * If a [TitleGenerator] is provided, the session title is automatically generated
 * from the first message (if the session doesn't already have a title).
 *
 * @param id the chat session ID (must already exist in the repository)
 * @param repository the repository for persistence operations
 * @param eventPublisher Spring's event publisher for broadcasting events
 * @param user the human user participant (author of USER messages, recipient of ASSISTANT messages)
 * @param agent the AI/system user participant (author of ASSISTANT messages, recipient of USER messages)
 * @param title the session title (included in events for UI display)
 * @param titleGenerator optional generator for auto-generating session title from first message
 * @param sessionEventAwaiter awaiter for handling session creation race conditions.
 *   Message persistence will wait for the session to be created rather than
 *   failing immediately if the session doesn't exist yet.
 * @param scope coroutine scope for async operations (defaults to IO dispatcher with SupervisorJob)
 */
class StoredConversation(
    override val id: String,
    private val repository: ChatSessionRepository,
    private val sessionEventAwaiter: SessionEventAwaiter,
    private val eventPublisher: ApplicationEventPublisher? = null,
    private val user: StoredUser? = null,
    private val agent: StoredUser? = null,
    private var title: String? = null,
    private val titleGenerator: TitleGenerator? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    override val assetTracker: AssetTracker = InMemoryAssetTracker()
) : Conversation {

    private val logger = LoggerFactory.getLogger(StoredConversation::class.java)

    /**
     * Returns true since this conversation is backed by persistent storage.
     */
    override fun persistent(): Boolean = true

    /**
     * Messages loaded from the repository.
     * Lazily refreshed on access.
     */
    override val messages: List<Message>
        get() = repository.getMessages(id).map { it.toMessage() }

    /**
     * Add a message using default [user] and [agent] for attribution based on role.
     *
     * - USER messages: from=[user], to=[agent]
     * - ASSISTANT messages: from=[agent], to=[user]
     * - SYSTEM messages: from=null, to=[user]
     *
     * This method blocks until the message is persisted to the DB.
     * Post-persistence tasks (title generation, PERSISTED event) run asynchronously.
     *
     * @param message the message to add
     * @return the message (returned after persistence completes)
     */
    override fun addMessage(message: Message): Message {
        val (from, to) = when (message.role) {
            MessageRole.USER -> user to agent
            MessageRole.ASSISTANT -> agent to user
            else -> null to user
        }
        return addMessageInternal(message, from, to)
    }

    /**
     * Add a message with explicit author attribution.
     *
     * The recipient is derived from role:
     * - USER/SYSTEM messages: to=[agent]
     * - ASSISTANT messages: to=[user]
     *
     * For full control over both from and to, use [addMessageFromTo].
     *
     * @param message the message to add
     * @param author the author of this message (must be StoredUser for persistence)
     * @return the message (returned immediately, before persistence completes)
     * @throws IllegalArgumentException if author is not null and not a StoredUser
     */
    override fun addMessageFrom(message: Message, author: User?): Message {
        val from = author?.toStoredUser("author")
        val to = when (message.role) {
            MessageRole.ASSISTANT -> user
            else -> agent
        }
        return addMessageInternal(message, from, to)
    }

    /**
     * Add a message with explicit author and recipient.
     *
     * Use this for multi-party chats where both sender and receiver need to be specified.
     *
     * @param message the message to add
     * @param from the author of this message (who sent it, must be StoredUser for persistence)
     * @param to the recipient of this message (who should receive it, must be StoredUser for persistence)
     * @return the message (returned immediately, before persistence completes)
     * @throws IllegalArgumentException if from or to is not null and not a StoredUser
     */
    override fun addMessageFromTo(
        message: Message,
        from: User?,
        to: User?
    ): Message {
        val fromUser = from?.toStoredUser("from")
        val toUser = to?.toStoredUser("to")
        return addMessageInternal(message, fromUser, toUser)
    }

    override fun last(n: Int): Conversation {
        // Return an in-memory snapshot of the last n messages
        val lastMessages = messages.takeLast(n)
        return object : Conversation {
            override val id: String = this@StoredConversation.id
            override val messages: List<Message> = lastMessages
            override val assetTracker: AssetTracker = this@StoredConversation.assetTracker
            override fun persistent(): Boolean = false
            override fun addMessage(message: Message): Message = throw UnsupportedOperationException("Snapshot is read-only")
            override fun last(n: Int): Conversation = this
        }
    }

    private fun User.toStoredUser(paramName: String): StoredUser {
        return this as? StoredUser
            ?: throw IllegalArgumentException(
                "$paramName must be a StoredUser for persistence. Got: ${this::class.simpleName}"
            )
    }

    private fun addMessageInternal(
        message: Message,
        from: StoredUser?,
        to: StoredUser?
    ): Message {
        val messageData = MessageData.from(message, messageId = UUIDv7.generateString())
        val isFirstMessage = messages.isEmpty()

        // Register interest in session creation BEFORE the async launch,
        // so we don't miss the event if it fires between the first attempt and the await
        val signal = sessionEventAwaiter.register(id)

        // Publish ADDED event synchronously before persistence
        eventPublisher?.publishEvent(
            MessageEvent.added(id, message, from?.id, to?.id, title)
        )

        // DB write — synchronous, blocks until persisted so that subsequent
        // reads via getMessages() see this message immediately
        val updatedSession = try {
            runBlocking {
                addMessageWithAwait(id, messageData, from, to, signal)
            }
        } catch (e: Exception) {
            logger.error("Failed to persist message to session {}: {}", id, e.message, e)
            eventPublisher?.publishEvent(
                MessageEvent.persistenceFailed(
                    conversationId = id,
                    content = message.content,
                    role = message.role,
                    error = e,
                    fromUserId = from?.id,
                    toUserId = to?.id,
                    title = title
                )
            )
            sessionEventAwaiter.unregister(id, signal)
            throw e
        }

        // Title generation + PERSISTED event — still async (not on critical path)
        scope.launch {
            try {
                val persistedMessage = updatedSession.messages.last().toMessage()

                // Generate title from first message if no title exists
                if (isFirstMessage && titleGenerator != null && title.isNullOrBlank()) {
                    try {
                        title = titleGenerator.generate(message)
                        repository.updateSessionTitle(id, title!!)
                        logger.debug("Generated title '{}' for session {}", title, id)
                    } catch (e: Exception) {
                        logger.warn("Failed to generate title for session {}: {}", id, e.message)
                    }
                }

                eventPublisher?.publishEvent(
                    MessageEvent.persisted(id, persistedMessage, from?.id, to?.id, title)
                )
                logger.debug("Message {} persisted to session {}", messageData.messageId, id)
            } catch (e: Exception) {
                logger.error("Failed to publish persistence event for session {}: {}", id, e.message, e)
            } finally {
                sessionEventAwaiter.unregister(id, signal)
            }
        }

        return message
    }

    /**
     * Attempt to add a message, waiting for the session to be created if it doesn't exist yet.
     *
     * On the first attempt, if the session is not found, this suspends until a
     * [SessionCreatedEvent] is received for this session, then retries once.
     */
    private suspend fun addMessageWithAwait(
        sessionId: String,
        messageData: MessageData,
        author: StoredUser?,
        recipient: StoredUser?,
        signal: CompletableDeferred<Unit>
    ): StoredSession {
        return try {
            repository.addMessage(sessionId, messageData, author, recipient)
        } catch (e: IllegalArgumentException) {
            if (e.message?.contains("Session not found") != true) throw e

            logger.warn(
                "Session {} not yet available for message {}, awaiting SessionCreatedEvent",
                sessionId, messageData.messageId
            )
            sessionEventAwaiter.awaitSession(signal)
            repository.addMessage(sessionId, messageData, author, recipient)
        }
    }
}