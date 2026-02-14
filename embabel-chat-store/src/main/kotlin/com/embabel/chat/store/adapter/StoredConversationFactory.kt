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
import com.embabel.chat.Conversation
import com.embabel.chat.ConversationFactory
import com.embabel.chat.ConversationStoreType
import com.embabel.chat.store.model.StoredUser
import com.embabel.chat.store.event.SessionEventAwaiter
import com.embabel.chat.store.repository.ChatSessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.springframework.context.ApplicationEventPublisher

/**
 * Factory for creating [StoredConversation] instances.
 *
 * Messages are persisted to a backing store via [ChatSessionRepository].
 *
 * ## Usage
 *
 * **1-1 chats** (user and agent defaults):
 * ```kotlin
 * val conversation = factory.createForParticipants(sessionId, user = human, agent = assistant)
 * conversation.addMessage(userMessage)  // from=human, to=assistant
 * conversation.addMessage(assistantMessage)  // from=assistant, to=human
 * ```
 *
 * **Multi-party chats** (explicit from/to per message):
 * ```kotlin
 * val conversation = factory.create(sessionId) as StoredConversation
 * conversation.addMessageFromTo(message, from = alice, to = bob)
 * conversation.addMessageFromTo(message, from = agent1, to = agent2)
 * ```
 *
 * ## Future: Multi-User and Multi-Agent Support
 *
 * The current model supports 1-1 chats with default user/agent participants.
 * For multi-party scenarios, use [StoredConversation.addMessageFromTo] with explicit
 * from/to per message.
 *
 * Planned enhancements:
 * - Group recipients (send to multiple users)
 * - Participant lists on conversations
 * - Agent-to-agent communication patterns
 *
 * @param repository the repository for persistence operations
 * @param sessionEventAwaiter awaiter for handling session creation race conditions
 * @param eventPublisher optional event publisher for message lifecycle events
 * @param titleGenerator optional generator for auto-generating session titles
 * @param scope coroutine scope for async operations
 */
class StoredConversationFactory @JvmOverloads constructor(
    private val repository: ChatSessionRepository,
    private val sessionEventAwaiter: SessionEventAwaiter,
    private val eventPublisher: ApplicationEventPublisher? = null,
    private val titleGenerator: TitleGenerator? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : ConversationFactory {

    override val storeType: ConversationStoreType = ConversationStoreType.STORED

    /**
     * Create a conversation with no default participants.
     *
     * Use [StoredConversation.addMessageFromTo] to specify from/to per message.
     */
    override fun create(id: String): Conversation {
        return createInternal(id, user = null, agent = null)
    }

    /**
     * Load an existing conversation from storage.
     *
     * If the session exists, returns a [StoredConversation] that will load
     * messages from the repository. The session owner becomes the default user
     * for message attribution.
     *
     * @param id the conversation/session ID to load
     * @return the conversation if found, null otherwise
     */
    override fun load(id: String): Conversation? {
        return repository.findBySessionId(id).orElse(null)?.let { session ->
            StoredConversation(
                id = id,
                repository = repository,
                sessionEventAwaiter = sessionEventAwaiter,
                eventPublisher = eventPublisher,
                user = session.owner,
                agent = null,
                title = session.session.title,
                titleGenerator = titleGenerator,
                scope = scope
            )
        }
    }

    /**
     * Create a conversation for a 1-1 chat between a user and an agent.
     *
     * Messages are automatically attributed based on role:
     * - USER messages: from=[user], to=[agent]
     * - ASSISTANT messages: from=[agent], to=[user]
     * - SYSTEM messages: from=null, to=[user]
     *
     * @param id the conversation/session ID
     * @param user the human user participant (must be a StoredUser for persistence)
     * @param agent the AI/system user participant (optional, must be a StoredUser if provided)
     * @param title the session title (included in events for UI display)
     * @throws IllegalArgumentException if user or agent is not a StoredUser
     */
    override fun createForParticipants(
        id: String,
        user: User,
        agent: User?,
        title: String?
    ): Conversation {
        val sessionUser = user as? StoredUser
            ?: throw IllegalArgumentException("user must be a StoredUser for persistence. Got: ${user::class.simpleName}")
        val sessionAgent = agent?.let {
            it as? StoredUser
                ?: throw IllegalArgumentException("agent must be a StoredUser for persistence. Got: ${it::class.simpleName}")
        }
        return createInternal(id, sessionUser, sessionAgent, title)
    }

    private fun createInternal(
        id: String,
        user: StoredUser?,
        agent: StoredUser?,
        title: String? = null
    ): Conversation {
        return StoredConversation(
            id = id,
            repository = repository,
            sessionEventAwaiter = sessionEventAwaiter,
            eventPublisher = eventPublisher,
            user = user,
            agent = agent,
            title = title,
            titleGenerator = titleGenerator,
            scope = scope
        )
    }
}