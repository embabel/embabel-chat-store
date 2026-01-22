package com.embabel.chat.store.repository

import com.embabel.chat.store.model.MessageData
import com.embabel.chat.store.model.SessionUser
import com.embabel.chat.store.model.StoredMessage
import com.embabel.chat.store.model.StoredSession
import java.util.Optional

/**
 * Repository for chat session persistence operations.
 *
 * This interface provides CRUD operations for chat sessions and their messages.
 * Sessions are owned by users (implementing SessionUser) and contain an ordered
 * list of messages.
 */
interface ChatSessionRepository {

    // ==================== Session CRUD ====================

    /**
     * Create a new session owned by the specified user.
     *
     * @param sessionId the session ID (should be UUIDv7 for chronological ordering)
     * @param owner the user who owns the session
     * @param title optional title for the session
     * @return the created session
     */
    fun createSession(sessionId: String, owner: SessionUser, title: String? = null): StoredSession

    /**
     * Create a new session with an initial message.
     *
     * This is a convenience method that combines session creation with adding
     * the first message in a single operation.
     *
     * @param sessionId the session ID (should be UUIDv7 for chronological ordering)
     * @param owner the user who owns the session
     * @param title optional title for the session
     * @param messageData the initial message data
     * @param messageAuthor optional author of the message (null for system messages)
     * @return the created session with the message
     */
    fun createSessionWithMessage(
        sessionId: String,
        owner: SessionUser,
        title: String? = null,
        messageData: MessageData,
        messageAuthor: SessionUser? = null
    ): StoredSession

    /**
     * Find a session by its ID.
     *
     * @param sessionId the session ID
     * @return the session if found, empty otherwise
     */
    fun findBySessionId(sessionId: String): Optional<StoredSession>

    /**
     * List all sessions owned by a user.
     *
     * @param userId the owner's user ID
     * @return list of sessions owned by the user
     */
    fun listSessionsForUser(userId: String): List<StoredSession>

    /**
     * Update the title of a session.
     *
     * @param sessionId the session ID
     * @param title the new title
     */
    fun updateSessionTitle(sessionId: String, title: String)

    /**
     * Delete a session and all its messages.
     *
     * @param sessionId the session ID
     */
    fun deleteSession(sessionId: String)

    // ==================== Message Operations ====================

    /**
     * Add a message to a session.
     *
     * @param sessionId the session ID
     * @param messageData the message data
     * @param author optional author of the message (null for system/assistant messages)
     * @return the updated session
     */
    fun addMessage(sessionId: String, messageData: MessageData, author: SessionUser? = null): StoredSession

    /**
     * Get all messages in a session.
     *
     * @param sessionId the session ID
     * @return list of messages in chronological order
     */
    fun getMessages(sessionId: String): List<StoredMessage>

    // ==================== Bulk Operations ====================

    /**
     * Delete all sessions (for testing).
     */
    fun deleteAll()
}
