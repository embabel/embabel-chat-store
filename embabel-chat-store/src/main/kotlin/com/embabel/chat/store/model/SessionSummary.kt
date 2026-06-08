package com.embabel.chat.store.model

import org.drivine.annotation.Count
import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.Root

/**
 * Lightweight session view for list displays: the session, its owner, and a
 * [messageCount] computed in the query — without loading the messages themselves.
 *
 * Rendering a user's session list needs each session's title, owner, and a "N
 * messages" badge, but not the message bodies. Loading [StoredSession] would pull
 * every message across the wire; this view returns one row per session with the
 * count folded in via [Count], so the payload stays flat regardless of how long
 * the conversations are — which matters when the database is a round-trip away.
 *
 * Like [StoredSession], [owner] is required (non-null), so the view counts and
 * returns only sessions that have an `OWNED_BY` edge.
 *
 * Example Neo4j structure:
 * ```
 * (session:ChatSession)-[:OWNED_BY]->(user:User)
 * (session:ChatSession)-[:HAS_MESSAGE]->(msg:StoredMessage)   // counted, not loaded
 * ```
 */
@GraphView
data class SessionSummary(
    @Root val session: SessionData,

    @GraphRelationship(type = "OWNED_BY", direction = Direction.OUTGOING)
    val owner: StoredUser,

    @Count(type = "HAS_MESSAGE", direction = Direction.OUTGOING)
    val messageCount: Long,
)