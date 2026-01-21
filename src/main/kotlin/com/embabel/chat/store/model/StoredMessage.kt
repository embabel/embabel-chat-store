package com.embabel.chat.store.model

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import java.time.Instant

/**
 * A message stored in a chat session.
 *
 * Messages are identified by UUIDv7 which provides chronological ordering
 * when sorted lexicographically.
 */
@NodeFragment(labels = ["StoredMessage"])
data class StoredMessage(
    /**
     * Unique message identifier (UUIDv7 recommended for chronological ordering).
     */
    @NodeId val messageId: String,

    /**
     * The role of the message sender: "user", "assistant", or "system".
     */
    val role: String,

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
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_SYSTEM = "system"
    }
}
