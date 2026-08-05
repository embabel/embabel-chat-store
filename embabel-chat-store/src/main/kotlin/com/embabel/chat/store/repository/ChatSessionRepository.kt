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
package com.embabel.chat.store.repository

import com.embabel.chat.store.model.AttachmentData
import com.embabel.chat.store.model.MessageData
import com.embabel.chat.store.model.SessionSummary
import com.embabel.chat.store.model.SimpleStoredMessage
import com.embabel.chat.store.model.StoredSession
import com.embabel.chat.store.model.StoredUser
import java.util.Optional

/**
 * Repository for chat session persistence operations.
 *
 * This interface provides CRUD operations for chat sessions and their messages.
 * Sessions are owned by users (implementing [StoredUser]) and contain an ordered
 * list of messages.
 */
interface ChatSessionRepository {

    // ==================== Session CRUD ====================

    /**
     * Create a new session owned by the specified user.
     *
     * @param sessionId the session ID (should be UUIDv7 for chronological ordering)
     * @param owner the user who owns the session (must implement [StoredUser])
     * @param title optional title for the session
     * @return the created session
     */
    fun createSession(sessionId: String, owner: StoredUser, title: String? = null): StoredSession

    /**
     * Create a new session with an initial message.
     *
     * This is a convenience method that combines session creation with adding
     * the first message in a single operation.
     *
     * @param sessionId the session ID (should be UUIDv7 for chronological ordering)
     * @param owner the user who owns the session (must implement [StoredUser])
     * @param title optional title for the session
     * @param messageData the initial message data
     * @param messageAuthor optional author of the message (who sent it)
     * @param messageRecipient optional recipient of the message (who should receive it)
     * @return the created session with the message
     */
    fun createSessionWithMessage(
        sessionId: String,
        owner: StoredUser,
        title: String? = null,
        messageData: MessageData,
        messageAuthor: StoredUser? = null,
        messageRecipient: StoredUser? = null
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
     * List sessions in descending `(lastActivityAt, sessionId)` order using an opaque keyset cursor.
     * The session ID is only a unique tie-breaker: arbitrary strings remain deterministic, while
     * UUIDv7 is still recommended by the creation API. Concurrent activity may move a session ahead
     * of an already-issued cursor; this method does not provide snapshot isolation across requests.
     */
    fun listSessionsForUser(userId: String, page: SessionPageRequest): SessionPage<StoredSession>

    /**
     * List a user's sessions as lightweight summaries — owner plus a message count
     * computed in the query, without loading the messages. Suited to session-list
     * UIs that show a "N messages" badge but not the conversation bodies.
     *
     * @param userId the owner's user ID
     * @return one [SessionSummary] per owned session
     */
    fun listSessionSummariesForUser(userId: String): List<SessionSummary>

    /**
     * Lightweight equivalent of [listSessionsForUser] with identical ordering and cursor semantics.
     */
    fun listSessionSummariesForUser(userId: String, page: SessionPageRequest): SessionPage<SessionSummary>

    /**
     * Count the sessions owned by a user, without loading them.
     *
     * @param userId the owner's user ID
     * @return the number of sessions owned by the user
     */
    fun countSessionsForUser(userId: String): Long

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
     * @param author optional author of the message (who sent it)
     * @param recipient optional recipient of the message (who should receive it)
     * @param attachments files attached to this message; empty for most messages
     * @return the updated session
     */
    fun addMessage(
        sessionId: String,
        messageData: MessageData,
        author: StoredUser? = null,
        recipient: StoredUser? = null,
        attachments: List<AttachmentData> = emptyList()
    ): StoredSession

    /**
     * Get all messages in a session.
     *
     * @param sessionId the session ID
     * @return list of messages in chronological order
     */
    fun getMessages(sessionId: String): List<SimpleStoredMessage>

    /**
     * Get the distinct users who authored a message in a session, skipping the
     * message nodes in between. Returns an empty list if the session does not exist
     * or has no attributed messages.
     *
     * @param sessionId the session ID
     * @return the distinct message authors
     */
    fun getParticipants(sessionId: String): List<StoredUser>

    // ==================== Message Updates ====================

    /**
     * Update the narration text for a message.
     * Finds the latest assistant message without narration in the given session.
     *
     * @param conversationId the session/conversation ID
     * @param narration the TTS-friendly narration text
     */
    fun updateMessageNarration(conversationId: String, narration: String)

    // ==================== Bulk Operations ====================

    /**
     * Delete all sessions (for testing).
     */
    fun deleteAll()
}
