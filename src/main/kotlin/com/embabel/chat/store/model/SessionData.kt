package com.embabel.chat.store.model

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import java.time.Instant

/**
 * Core session data stored as a Neo4j node.
 *
 * Sessions are identified by UUIDv7 which provides chronological ordering
 * when sorted lexicographically.
 */
@NodeFragment(labels = ["ChatSession"])
data class SessionData(
    /**
     * Unique session identifier (UUIDv7 recommended).
     */
    @NodeId val sessionId: String,

    /**
     * Optional title for the session (like ChatGPT/Claude conversation titles).
     */
    val title: String?,

    /**
     * When the session was created.
     */
    val createdAt: Instant,

    /**
     * Optional metadata for application-specific extensions.
     */
    val metadata: Map<String, Any>? = null
)
