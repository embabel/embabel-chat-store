package com.embabel.chat.store.model

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.Root
import org.drivine.annotation.SortedBy

/**
 * Complete session view including owner and messages.
 *
 * This is a Drivine GraphView that aggregates:
 * - The session data (root node)
 * - The owning user (polymorphic via [StoredUser] interface)
 * - All messages in the session (sorted by messageId for chronological order)
 *
 * Example Neo4j structure:
 * ```
 * (session:ChatSession)-[:OWNED_BY]->(user:User)
 * (session:ChatSession)-[:HAS_MESSAGE]->(msg:StoredMessage)
 * ```
 */
@GraphView
data class StoredSession(
    /**
     * The session node data.
     */
    @Root val session: SessionData,

    /**
     * The user who owns this session.
     * Polymorphic - can be any type implementing [StoredUser].
     */
    @GraphRelationship(type = "OWNED_BY", direction = Direction.OUTGOING)
    val owner: StoredUser,

    /**
     * Messages in this session, sorted by messageId (UUIDv7 = chronological order).
     */
    @GraphRelationship(type = "HAS_MESSAGE", direction = Direction.OUTGOING)
    @SortedBy("message.messageId")
    val messages: List<SimpleStoredMessage> = emptyList()
) {
    /**
     * Returns a copy of this session with an additional message.
     */
    fun withMessage(message: SimpleStoredMessage): StoredSession =
        copy(messages = messages + message)
}

/**
 * Lightweight session reference for write operations.
 * Contains only the session ID — used as the root of [NewMessage] to create
 * the HAS_MESSAGE relationship without loading or modifying session properties.
 *
 * @see NewMessageInSession
 */
@NodeFragment(labels = ["ChatSession"])
data class SessionRef(
    @NodeId val sessionId: String
)
