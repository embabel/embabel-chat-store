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
import com.embabel.chat.store.embedding.MessageEmbedder
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
import org.slf4j.LoggerFactory
import com.embabel.chat.store.util.UUIDv7
import org.springframework.context.ApplicationEventPublisher
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A [Conversation] implementation that persists messages to Neo4j via [ChatSessionRepository].
 *
 * This adapter provides persistence for conversations. Messages added via [addMessage]
 * are persisted **asynchronously** to avoid blocking on DB latency, while an in-memory
 * buffer ensures reads are always consistent:
 *
 * 1. `addMessage()` adds the message to an in-memory pending buffer and launches async DB persistence
 * 2. `messages` returns the merged view: DB messages + pending buffer (deduplicated by messageId)
 * 3. On success: the message is removed from the pending buffer and [MessageEvent] PERSISTED is published
 * 4. On failure: the message stays in the pending buffer and [MessageEvent] PERSISTENCE_FAILED is published
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
 * An interim title is set from the first user message content so the session appears
 * immediately in the UI. If a [TitleGenerator] is provided, it re-evaluates the title
 * every [titleAfterMessageCount] messages, keeping it if still relevant.
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
 * @param messageEmbedder optional embedder invoked inline within the async persistence
 *   coroutine. When configured, the embedding is computed before the single DB write so
 *   the message lands with its vector in one round-trip. Embedding failures are caught
 *   and the message is persisted with a null embedding — messages are never lost. When
 *   null, messages persist without any embedding (same behaviour as before vector
 *   embedding was introduced).
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
    private val titleAfterMessageCount: Int = 1,
    private val interimTitleMaxLength: Int = TitleGenerator.DEFAULT_MAX_LENGTH,
    private val messageEmbedder: MessageEmbedder? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    override val assetTracker: AssetTracker = InMemoryAssetTracker()
) : Conversation {

    private val logger = LoggerFactory.getLogger(StoredConversation::class.java)

    private val pendingMessages = ConcurrentLinkedQueue<MessageData>()

    /**
     * Returns true since this conversation is backed by persistent storage.
     */
    override fun persistent(): Boolean = true

    /**
     * Messages loaded from the repository, merged with any pending (not yet persisted) messages.
     * Pending messages that have already appeared in the DB result are deduplicated by messageId.
     */
    override val messages: List<Message>
        get() {
            val dbMessages = repository.getMessages(id)
            val dbMessageIds = dbMessages.mapTo(HashSet()) { it.messageId }
            val pending = pendingMessages.filter { it.messageId !in dbMessageIds }
            return dbMessages.map { it.toMessage() } + pending.map { it.toMessage() }
        }

    /**
     * Add a message using default [user] and [agent] for attribution based on role.
     *
     * - USER messages: from=[user], to=[agent]
     * - ASSISTANT messages: from=[agent], to=[user]
     * - SYSTEM messages: from=null, to=[user]
     *
     * The message is added to an in-memory pending buffer and returned immediately.
     * DB persistence runs asynchronously.
     *
     * @param message the message to add
     * @return the message (returned immediately, before persistence completes)
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

        // Generate an interim title from the first user message so the session
        // appears immediately in the UI.  The LLM will replace it later once
        // enough conversation context is available.
        if (title.isNullOrBlank() && message.role == MessageRole.USER) {
            title = truncateForTitle(message.content, interimTitleMaxLength)
            try {
                repository.updateSessionTitle(id, title!!)
                logger.debug("Interim title '{}' for session {}", title, id)
            } catch (e: Exception) {
                logger.warn("Failed to set interim title for session {}: {}", id, e.message)
            }
        }

        // Register interest in session creation BEFORE the async launch,
        // so we don't miss the event if it fires between the first attempt and the await
        val signal = sessionEventAwaiter.register(id)

        // Publish ADDED event synchronously before persistence
        eventPublisher?.publishEvent(
            MessageEvent.added(id, message, from?.id, to?.id, title)
        )

        // Add to pending buffer so getMessages() returns this message immediately
        pendingMessages.add(messageData)

        // DB write — asynchronous, non-blocking. The pending buffer ensures
        // consistent reads while the write is in flight.
        scope.launch {
            try {
                val messageDataForDb = embedMessageData(message, messageData)
                val updatedSession = addMessageWithAwait(id, messageDataForDb, from, to, signal)

                // Persisted — remove from pending buffer (DB is now the source of truth)
                pendingMessages.remove(messageData)

                // PERSISTED event
                try {
                    val persistedMessage = updatedSession.messages.last().toMessage()
                    eventPublisher?.publishEvent(
                        MessageEvent.persisted(id, persistedMessage, from?.id, to?.id, title)
                    )
                    logger.debug("Message {} persisted to session {}", messageData.messageId, id)
                } catch (e: Exception) {
                    logger.error("Failed to publish persistence event for session {}: {}", id, e.message, e)
                }

                // Title re-evaluation
                val messageCount = updatedSession.messages.size
                if (messageCount >= titleAfterMessageCount && titleGenerator != null
                    && messageCount % titleAfterMessageCount == 0) {
                    try {
                        val allMessages = updatedSession.messages.map { it.toMessage() }
                        val newTitle = titleGenerator.generate(allMessages, title, user?.id)
                        if (newTitle != title) {
                            title = newTitle
                            repository.updateSessionTitle(id, title!!)
                            logger.debug("Updated title '{}' for session {} (at {} messages)", title, id, messageCount)
                            eventPublisher?.publishEvent(
                                MessageEvent.persisted(id, allMessages.last(), from?.id, to?.id, title)
                            )
                        }
                    } catch (e: Exception) {
                        logger.warn("Failed to generate title for session {}: {}", id, e.message)
                    }
                }
            } catch (e: Exception) {
                // Message stays in pending buffer on failure — still visible in reads
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
            } finally {
                sessionEventAwaiter.unregister(id, signal)
            }
        }

        return message
    }

    /**
     * Return [messageData] with the embedding fields populated, or the original
     * unchanged if the embedder declined or failed. Embedding failure is never
     * fatal: the message is always persisted, with a null embedding if needed.
     */
    private suspend fun embedMessageData(message: Message, messageData: MessageData): MessageData {
        val embedder = messageEmbedder ?: return messageData
        return try {
            val result = embedder.embed(message) ?: return messageData
            messageData.copy(embedding = result.vector, embeddingModel = result.model)
        } catch (e: Exception) {
            logger.warn(
                "Embedding failed for message {} in session {}: {}",
                messageData.messageId, id, e.message, e
            )
            messageData
        }
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

    companion object {
        /**
         * Truncate user message content into a short interim title.
         */
        internal fun truncateForTitle(content: String, maxLength: Int): String {
            val cleaned = content.trim()
                .replace("\n", " ")
                .replace(Regex("\\s+"), " ")
            return if (cleaned.length <= maxLength) {
                cleaned
            } else {
                cleaned.take(maxLength - 3).trimEnd() + "..."
            }
        }
    }
}
