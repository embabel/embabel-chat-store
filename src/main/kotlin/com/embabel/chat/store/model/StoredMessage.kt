package com.embabel.chat.store.model

import com.embabel.chat.Role
import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.Root
import java.time.Instant

/**
 * Raw message node data.
 *
 * Use this directly when you don't need the author relationship loaded,
 * or use [StoredMessage] when you need the full graph view with author.
 */
@NodeFragment(labels = ["StoredMessage"])
data class MessageData(
    /**
     * Unique message identifier (UUIDv7 recommended for chronological ordering).
     */
    @NodeId val messageId: String,

    /**
     * The role of the message sender.
     */
    val role: Role,

    /**
     * The text content of the message.
     */
    val content: String,

    /**
     * When the message was created.
     */
    val createdAt: Instant,

    /**
     * Optional metadata for application-specific extensions.
     */
    val metadata: Map<String, Any>? = null
)

/**
 * A message stored in a chat session, with its author and recipient relationships.
 *
 * This is a GraphView that includes:
 * - The message node data ([MessageData])
 * - The optional author relationship (who sent the message)
 * - The optional recipient relationship (who should receive the message)
 *
 * Example Neo4j structure:
 * ```
 * (msg:StoredMessage)-[:AUTHORED_BY]->(from:SessionUser)
 * (msg:StoredMessage)-[:SENT_TO]->(to:SessionUser)
 * ```
 *
 * If you don't need relationships loaded, you can use [MessageData] directly
 * in your own GraphView for lighter reads.
 */
@GraphView
data class StoredMessage(
    /**
     * The message node data.
     */
    @Root val message: MessageData,

    /**
     * The user who authored this message.
     * Null for system-generated messages (assistant responses) until a system user is assigned.
     */
    @GraphRelationship(type = "AUTHORED_BY", direction = Direction.OUTGOING)
    val author: SessionUser? = null,

    /**
     * The user who should receive this message.
     * Used for routing (e.g., WebSocket notifications).
     */
    @GraphRelationship(type = "SENT_TO", direction = Direction.OUTGOING)
    val recipient: SessionUser? = null
) {
    // Convenience accessors for common properties
    val messageId: String get() = message.messageId
    val role: Role get() = message.role
    val content: String get() = message.content
    val createdAt: Instant get() = message.createdAt
    val metadata: Map<String, Any>? get() = message.metadata
}
