package com.embabel.chat.store.model

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.Root

/**
 * Delete-only view of a session used for cascading hard deletes.
 *
 * The shape of this view *is* the cascade boundary: Drivine follows only the
 * relationships the view declares, so a cascade delete through it traverses
 * the session and its messages and nothing else.
 *
 * Deliberately narrow:
 * - The child is [MessageData] (the bare `:StoredMessage` node fragment), **not**
 *   [SimpleStoredMessage] (which carries AUTHORED_BY/SENT_TO → [StoredUser]).
 *   A DETACH DELETE of each message node drops those edges while leaving the
 *   `:User` nodes intact.
 * - The OWNED_BY owner is omitted entirely. Because the view declares only
 *   HAS_MESSAGE, the cascade physically cannot reach `:User` — the safety is
 *   structural, not a flag.
 *
 * Neo4j structure traversed:
 * ```
 * (session:ChatSession)-[:HAS_MESSAGE]->(msg:StoredMessage)
 * ```
 *
 * @see com.embabel.chat.store.model.StoredSession for the full read view
 */
@GraphView
data class DeletableSession(
    /**
     * The session node (id = sessionId).
     */
    @Root val session: SessionData,

    /**
     * The session's messages, as bare `:StoredMessage` node fragments.
     */
    @GraphRelationship(type = "HAS_MESSAGE", direction = Direction.OUTGOING)
    val messages: List<MessageData> = emptyList()
)