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
 * **1-1 chats** (default author for all user messages):
 * ```kotlin
 * val conversation = factory.createForUser(sessionId, currentUser)
 * conversation.addMessage(userMessage)  // uses currentUser as author
 * ```
 *
 * **Group chats** (explicit author per message):
 * ```kotlin
 * val conversation = factory.create(sessionId)
 * conversation.addMessageFrom(aliceMessage, alice)
 * conversation.addMessageFrom(bobMessage, bob)
 * ```
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
     * Create a conversation with no default author.
     *
     * Use [Conversation.addMessageFrom] to specify author per message,
     * or for conversations where author attribution is not needed.
     */
    override fun create(id: String): Conversation {
        return createInternal(id, sessionUser = null)
    }

    /**
     * Create a conversation with a default author for user messages.
     *
     * Use this for 1-1 chats where all user messages come from the same user.
     * The provided user is used as the author for messages added via [Conversation.addMessage].
     *
     * @param id the conversation/session ID
     * @param sessionUser the default author for user messages
     */
    fun createForUser(id: String, sessionUser: SessionUser): Conversation {
        return createInternal(id, sessionUser)
    }

    private fun createInternal(id: String, sessionUser: SessionUser?): Conversation {
        return StoredConversation(
            id = id,
            repository = repository,
            eventPublisher = eventPublisher,
            sessionUser = sessionUser,
            titleGenerator = titleGenerator,
            assetTracker = InMemoryAssetTracker(),
            scope = scope
        )
    }
}