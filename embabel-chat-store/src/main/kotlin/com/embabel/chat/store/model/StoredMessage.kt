package com.embabel.chat.store.model

import com.embabel.chat.AssistantMessage
import com.embabel.chat.Message
import com.embabel.chat.MessageRole
import com.embabel.chat.StoredMessage
import com.embabel.chat.SystemMessage
import com.embabel.chat.UserMessage
import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.Root
import java.time.Instant
import java.util.UUID

/**
 * Raw message node data for Neo4j persistence.
 *
 * Use this directly when you don't need the author relationship loaded,
 * or use [SimpleStoredMessage] when you need the full graph view with author.
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
    val role: MessageRole,

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
) {
    companion object {
        /**
         * Create MessageData from any [StoredMessage] (including agent-api Message types).
         */
        @JvmStatic
        @JvmOverloads
        fun from(
            message: StoredMessage,
            messageId: String = UUID.randomUUID().toString()
        ): MessageData = MessageData(
            messageId = messageId,
            role = message.role,
            content = message.content,
            createdAt = message.timestamp
        )
    }

    /**
     * Convert to a rich agent-api [Message] type.
     */
    fun toMessage(): Message = when (role) {
        MessageRole.USER -> UserMessage(content = content, timestamp = createdAt)
        MessageRole.ASSISTANT -> AssistantMessage(content = content, timestamp = createdAt)
        MessageRole.SYSTEM -> SystemMessage(content = content, timestamp = createdAt)
    }
}

/**
 * A message stored in a chat session, with its author and recipient relationships.
 * Implements [StoredMessage] for compatibility with agent-api.
 *
 * This is a GraphView that includes:
 * - The message node data ([MessageData])
 * - The optional author relationship (who sent the message)
 * - The optional recipient relationship (who should receive the message)
 *
 * Example Neo4j structure:
 * ```
 * (msg:StoredMessage)-[:AUTHORED_BY]->(from:User)
 * (msg:StoredMessage)-[:SENT_TO]->(to:User)
 * ```
 *
 * If you don't need relationships loaded, you can use [MessageData] directly
 * in your own GraphView for lighter reads.
 */
@GraphView
data class SimpleStoredMessage(
    /**
     * The message node data.
     */
    @Root val message: MessageData,

    /**
     * The user who authored this message.
     * Null for system-generated messages (assistant responses) until a system user is assigned.
     */
    @GraphRelationship(type = "AUTHORED_BY", direction = Direction.OUTGOING)
    val author: StoredUser? = null,

    /**
     * The user who should receive this message.
     * Used for routing (e.g., WebSocket notifications).
     */
    @GraphRelationship(type = "SENT_TO", direction = Direction.OUTGOING)
    val recipient: StoredUser? = null
) : StoredMessage {
    // Convenience accessors for common properties
    val messageId: String get() = message.messageId
    override val role: MessageRole get() = message.role
    override val content: String get() = message.content
    override val timestamp: Instant get() = message.createdAt
    val metadata: Map<String, Any>? get() = message.metadata

    /**
     * Convert to a rich agent-api [Message] type.
     */
    fun toMessage(): Message = message.toMessage()
}