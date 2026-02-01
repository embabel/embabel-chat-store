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
 *     sessionUser = currentUser,
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
 * @param sessionUser optional user for attributing user messages
 * @param titleGenerator optional generator for auto-generating session title from first message
 * @param assetTracker tracker for conversation assets (defaults to in-memory)
 * @param scope coroutine scope for async operations (defaults to IO dispatcher with SupervisorJob)
 */
class StoredConversation(
    override val id: String,
    private val repository: ChatSessionRepository,
    private val eventPublisher: ApplicationEventPublisher? = null,
    private val sessionUser: SessionUser? = null,
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
     * Add a message to the conversation asynchronously using the default session user.
     *
     * For USER role messages, the default [sessionUser] is used as the author.
     * For other roles (ASSISTANT, SYSTEM), no author is attributed.
     *
     * This method returns immediately. The message is persisted in the background,
     * and events are published on success or failure.
     *
     * @param message the message to add
     * @return the message (returned immediately, before persistence completes)
     */
    override fun addMessage(message: Message): Message {
        val author = when (message.role) {
            Role.USER -> sessionUser
            else -> null
        }
        return addMessageInternal(message, author)
    }

    /**
     * Add a message with explicit author attribution.
     *
     * Use this for group chats or when the author differs per message.
     * The provided author must be a [SessionUser] for persistence.
     *
     * @param message the message to add
     * @param author the author of this message (must be SessionUser for persistence, null for system/assistant)
     * @return the message (returned immediately, before persistence completes)
     * @throws IllegalArgumentException if author is not null and not a SessionUser
     */
    override fun addMessageFrom(message: Message, author: MessageAuthor?): Message {
        val sessionUserAuthor = when {
            author == null -> null
            author is SessionUser -> author
            else -> throw IllegalArgumentException(
                "Author must be a SessionUser for persistence. Got: ${author::class.simpleName}"
            )
        }
        return addMessageInternal(message, sessionUserAuthor)
    }

    private fun addMessageInternal(message: Message, author: SessionUser?): Message {
        val messageData = message.toMessageData(
            messageId = UUID.randomUUID().toString()
        )
        val isFirstMessage = messages.isEmpty()

        scope.launch {
            try {
                val updatedSession = repository.addMessage(id, messageData, author)
                val persistedMessage = updatedSession.messages.last().toMessage()

                // Generate title from first message if no title exists
                if (isFirstMessage && titleGenerator != null) {
                    val session = repository.findBySessionId(id).orElse(null)
                    if (session?.session?.title.isNullOrBlank()) {
                        try {
                            val title = titleGenerator.generate(message)
                            repository.updateSessionTitle(id, title)
                            logger.debug("Generated title '{}' for session {}", title, id)
                        } catch (e: Exception) {
                            logger.warn("Failed to generate title for session {}: {}", id, e.message)
                        }
                    }
                }

                eventPublisher?.publishEvent(
                    MessageEvent.persisted(id, persistedMessage)
                )
                logger.debug("Message {} persisted to session {}", messageData.messageId, id)
            } catch (e: Exception) {
                logger.error("Failed to persist message to session {}: {}", id, e.message, e)
                eventPublisher?.publishEvent(
                    MessageEvent.persistenceFailed(
                        conversationId = id,
                        content = message.content,
                        role = message.role,
                        error = e
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
