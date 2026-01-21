package com.embabel.chat.store.model

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.Root

/**
 * Complete session view including owner and messages.
 *
 * This is a Drivine GraphView that aggregates:
 * - The session data (root node)
 * - The owning user (polymorphic via SessionUser interface)
 * - All messages in the session (sorted by messageId for chronological order)
 *
 * Example Neo4j structure:
 * ```
 * (session:ChatSession)-[:OWNED_BY]->(user:SessionUser)
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
     * Polymorphic - can be any type implementing SessionUser.
     */
    @GraphRelationship(type = "OWNED_BY", direction = Direction.OUTGOING)
    val owner: SessionUser,

    /**
     * Messages in this session, sorted by messageId (UUIDv7 = chronological order).
     */
    @GraphRelationship(type = "HAS_MESSAGE", direction = Direction.OUTGOING)
    val messages: List<StoredMessage> = emptyList()
) {
    /**
     * Returns a copy of this session with an additional message.
     */
    fun withMessage(message: StoredMessage): StoredSession =
        copy(messages = messages + message)
}
