package com.embabel.chat.store.adapter

import com.embabel.chat.Conversation
import com.embabel.chat.ConversationFactory
import com.embabel.chat.ConversationStoreType
import com.embabel.chat.store.model.SessionUser
import com.embabel.chat.store.repository.ChatSessionRepository
import com.embabel.chat.support.InMemoryAssetTracker
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
 * @param eventPublisher optional event publisher for message lifecycle events
 * @param titleGenerator optional generator for auto-generating session titles
 * @param scope coroutine scope for async operations
 */
class StoredConversationFactory @JvmOverloads constructor(
    private val repository: ChatSessionRepository,
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
     * Create a conversation for a 1-1 chat between a user and an agent.
     *
     * Messages are automatically attributed based on role:
     * - USER messages: from=[user], to=[agent]
     * - ASSISTANT messages: from=[agent], to=[user]
     * - SYSTEM messages: from=null, to=[user]
     *
     * @param id the conversation/session ID
     * @param user the human user participant
     * @param agent the AI/system user participant (optional, can be set later)
     */
    @JvmOverloads
    fun createForParticipants(id: String, user: SessionUser, agent: SessionUser? = null): Conversation {
        return createInternal(id, user, agent)
    }

    private fun createInternal(id: String, user: SessionUser?, agent: SessionUser?): Conversation {
        return StoredConversation(
            id = id,
            repository = repository,
            eventPublisher = eventPublisher,
            user = user,
            agent = agent,
            titleGenerator = titleGenerator,
            assetTracker = InMemoryAssetTracker(),
            scope = scope
        )
    }
}