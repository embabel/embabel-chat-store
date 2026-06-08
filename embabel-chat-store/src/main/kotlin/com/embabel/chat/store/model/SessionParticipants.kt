package com.embabel.chat.store.model

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphPath
import org.drivine.annotation.GraphView
import org.drivine.annotation.Hop
import org.drivine.annotation.Root

/**
 * The distinct set of users who authored at least one message in a session.
 *
 * Participants live two hops from the session — `HAS_MESSAGE` to a message, then
 * `AUTHORED_BY` to its author — with the message node in the middle. [GraphPath]
 * traverses both hops and maps only the far node, so the intermediate
 * `:StoredMessage` nodes are never materialised, and an author who wrote many
 * messages is de-duplicated to a single entry.
 *
 * [participants] is polymorphic ([StoredUser]); Drivine resolves each author to
 * its concrete subtype by node labels, exactly as for a direct relationship.
 *
 * Path traversed:
 * ```
 * (session:ChatSession)-[:HAS_MESSAGE]->(:StoredMessage)-[:AUTHORED_BY]->(user:User)
 * ```
 */
@GraphView
data class SessionParticipants(
    @Root val session: SessionData,

    @GraphPath([
        Hop(type = "HAS_MESSAGE", direction = Direction.OUTGOING, label = "StoredMessage"),
        Hop(type = "AUTHORED_BY", direction = Direction.OUTGOING, label = "User"),
    ])
    val participants: List<StoredUser> = emptyList(),
)
