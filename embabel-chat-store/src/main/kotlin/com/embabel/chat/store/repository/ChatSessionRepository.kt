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
     * @param order the sort order; defaults to newest-created first
     * @return list of sessions owned by the user
     */
    fun listSessionsForUser(
        userId: String,
        order: SessionOrder = SessionOrder.CREATED,
    ): List<StoredSession>

    /**
     * List one page of sessions in [SessionPageRequest.order], using an opaque keyset cursor.
     *
     * Under [SessionOrder.CREATED] the keyset is `sessionId` descending, which is creation order
     * for UUIDv7 IDs; under [SessionOrder.LAST_ACTIVITY] it is `(lastActivityAt, sessionId)`
     * descending, with the ID acting only as a unique tie-breaker. A cursor is bound to the order
     * that issued it and is rejected if replayed against the other.
     *
     * There is no snapshot isolation across requests: under [SessionOrder.LAST_ACTIVITY],
     * concurrent activity may move a session ahead of an already-issued cursor, so it can be seen
     * twice or missed. [SessionOrder.CREATED] keys on an immutable value and so is stable.
     */
    fun listSessionsForUser(userId: String, page: SessionPageRequest): SessionPage<StoredSession> =
        SessionPaging.inMemory(listSessionsForUser(userId, page.order), page) { it.session }

    /**
     * List a user's sessions as lightweight summaries — owner plus a message count
     * computed in the query, without loading the messages. Suited to session-list
     * UIs that show a "N messages" badge but not the conversation bodies.
     *
     * @param userId the owner's user ID
     * @param order the sort order; defaults to newest-created first
     * @return one [SessionSummary] per owned session
     */
    fun listSessionSummariesForUser(
        userId: String,
        order: SessionOrder = SessionOrder.CREATED,
    ): List<SessionSummary>

    /**
     * Lightweight equivalent of [listSessionsForUser] with identical ordering and cursor semantics.
     */
    fun listSessionSummariesForUser(userId: String, page: SessionPageRequest): SessionPage<SessionSummary> =
        SessionPaging.inMemory(listSessionSummariesForUser(userId, page.order), page) { it.session }

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
     * Set the narration text on a specific message.
     *
     * Narration is enrichment, not activity: it deliberately does not advance the session's
     * `lastActivityAt`, so adding narration never reorders a session in a listing ordered by
     * [SessionOrder.LAST_ACTIVITY], and never reorders the message within its thread.
     *
     * @param sessionId the session owning the message
     * @param messageId the message to narrate, as minted when it was added
     * @param narration the TTS-friendly narration text
     * @return true if the message was found and updated, false otherwise
     */
    fun updateMessageNarration(sessionId: String, messageId: String, narration: String): Boolean

    /**
     * Update the narration text for a message.
     * Finds the latest assistant message without narration in the given session.
     *
     * @param conversationId the session/conversation ID
     * @param narration the TTS-friendly narration text
     */
    @Deprecated(
        "Guesses which message to narrate, and races the asynchronous message write: a " +
            "narration arriving before its message is persisted attaches to the previous " +
            "un-narrated assistant message. Use the messageId-keyed overload.",
        ReplaceWith("updateMessageNarration(conversationId, messageId, narration)"),
    )
    fun updateMessageNarration(conversationId: String, narration: String)

    // ==================== Bulk Operations ====================

    /**
     * Delete all sessions (for testing).
     */
    fun deleteAll()
}
