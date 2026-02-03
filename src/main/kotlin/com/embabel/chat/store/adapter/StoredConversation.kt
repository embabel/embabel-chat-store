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

import com.embabel.chat.AssetTracker
import com.embabel.chat.Conversation
import com.embabel.chat.Message
import com.embabel.chat.MessageAuthor
import com.embabel.chat.Role
import com.embabel.chat.event.MessageEvent
import com.embabel.chat.store.model.SessionUser
import com.embabel.chat.store.repository.ChatSessionRepository
import com.embabel.chat.support.InMemoryAssetTracker
import com.embabel.chat.support.InMemoryConversation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID

/**
 * A [Conversation] implementation that persists messages to Neo4j via [ChatSessionRepository].
 *
 * This adapter bridges embabel-agent's Conversation interface with embabel-chat-store's
 * persistence layer. Messages added via [addMessage] are persisted **asynchronously**:
 *
 * 1. `addMessage()` returns immediately (non-blocking)
 * 2. Message is converted to [MessageData] and persisted in background
 * 3. On success: [MessageEvent] with status PERSISTED is published
 * 4. On failure: [MessageEvent] with status PERSISTENCE_FAILED is published
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
 * ## Usage
 *
 * ```kotlin
 * val conversation = StoredConversation(
 *     id = "session-123",
 *     repository = chatSessionRepository,
 *     eventPublisher = applicationEventPublisher,
 *     user = currentUser,
 *     agent = assistantUser,
 *     titleGenerator = LlmTitleGenerator { prompt -> llm.generate(prompt) }
 * )
 *
 * // Returns immediately - persistence happens async
 * conversation.addMessage(UserMessage("Hello!"))
 *
 * // Subscribe to events for persistence confirmation
 * @EventListener
 * fun onPersisted(event: MessageEvent) {
 *     if (event.status == MessageStatus.PERSISTED) { ... }
 * }
 * ```
 *
 * @param id the chat session ID (must already exist in the repository)
 * @param repository the repository for persistence operations
 * @param eventPublisher Spring's event publisher for broadcasting events
 * @param user the human user participant (author of USER messages, recipient of ASSISTANT messages)
 * @param agent the AI/system user participant (author of ASSISTANT messages, recipient of USER messages)
 * @param title the session title (included in events for UI display)
 * @param titleGenerator optional generator for auto-generating session title from first message
 * @param assetTracker tracker for conversation assets (defaults to in-memory)
 * @param scope coroutine scope for async operations (defaults to IO dispatcher with SupervisorJob)
 */
class StoredConversation(
    override val id: String,
    private val repository: ChatSessionRepository,
    private val eventPublisher: ApplicationEventPublisher? = null,
    private val user: SessionUser? = null,
    private val agent: SessionUser? = null,
    private var title: String? = null,
    private val titleGenerator: TitleGenerator? = null,
    override val assetTracker: AssetTracker = InMemoryAssetTracker(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) : Conversation {

    private val logger = LoggerFactory.getLogger(StoredConversation::class.java)

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
     * This method returns immediately. The message is persisted in the background,
     * and events are published on success or failure.
     *
     * @param message the message to add
     * @return the message (returned immediately, before persistence completes)
     */
    override fun addMessage(message: Message): Message {
        val (from, to) = when (message.role) {
            Role.USER -> user to agent
            Role.ASSISTANT -> agent to user
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
     * @param author the author of this message (must be SessionUser for persistence)
     * @return the message (returned immediately, before persistence completes)
     * @throws IllegalArgumentException if author is not null and not a SessionUser
     */
    override fun addMessageFrom(message: Message, author: MessageAuthor?): Message {
        val from = when {
            author == null -> null
            author is SessionUser -> author
            else -> throw IllegalArgumentException(
                "Author must be a SessionUser for persistence. Got: ${author::class.simpleName}"
            )
        }
        val to = when (message.role) {
            Role.ASSISTANT -> user
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
     * @param from the author of this message (who sent it, must be SessionUser for persistence)
     * @param to the recipient of this message (who should receive it, must be SessionUser for persistence)
     * @return the message (returned immediately, before persistence completes)
     * @throws IllegalArgumentException if from or to is not null and not a SessionUser
     */
    override fun addMessageFromTo(message: Message, from: MessageAuthor?, to: MessageAuthor?): Message {
        val fromUser = from?.toSessionUser("from")
        val toUser = to?.toSessionUser("to")
        return addMessageInternal(message, fromUser, toUser)
    }

    private fun MessageAuthor.toSessionUser(paramName: String): SessionUser {
        return this as? SessionUser
            ?: throw IllegalArgumentException(
                "$paramName must be a SessionUser for persistence. Got: ${this::class.simpleName}"
            )
    }

    private fun addMessageInternal(message: Message, from: SessionUser?, to: SessionUser?): Message {
        val messageData = message.toMessageData(
            messageId = UUID.randomUUID().toString()
        )
        val isFirstMessage = messages.isEmpty()

        // Publish ADDED event synchronously before async persistence
        eventPublisher?.publishEvent(
            MessageEvent.added(id, message, from?.id, to?.id, title)
        )

        scope.launch {
            try {
                val updatedSession = repository.addMessage(id, messageData, from, to)
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
            }
        }

        return message
    }

    override fun persistent(): Boolean = true

    /**
     * Create a non-persistent view of the last n messages.
     *
     * Returns an [InMemoryConversation] since the view doesn't need persistence.
     */
    override fun last(n: Int): Conversation {
        return InMemoryConversation(
            messages = messages.takeLast(n),
            id = id,
            persistent = false,
            assets = assetTracker
        )
    }
}
