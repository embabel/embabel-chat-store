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
package com.embabel.chat.store.model

import org.drivine.annotation.Count
import org.drivine.annotation.Direction
import org.drivine.annotation.GraphPath
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.Hop
import org.drivine.annotation.Root

/**
 * Lightweight session view for list displays: the session, its owner, a [messageCount] and who
 * [startedBy] the session, computed in the query — without loading the messages themselves.
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
 * (session)-[:HAS_MESSAGE]->(first:StoredMessage)-[:AUTHORED_BY]->(starter:User)   // the earliest authored message's author
 * (session)-[:PART_OF]->(thread:ChatSession)   // optional
 * (session)-[:WITH]->(agent:User)              // optional
 * ```
 */
@GraphView
data class SessionSummary(
    @Root val session: SessionData,

    @GraphRelationship(type = "OWNED_BY", direction = Direction.OUTGOING)
    val owner: StoredUser,

    @Count(type = "HAS_MESSAGE", direction = Direction.OUTGOING)
    val messageCount: Long,

    /**
     * Who started the session: the author of the earliest message that has one, read in the same
     * query. A message with no author — a system message opening the session, say — is passed
     * over, so a session opened by a greeting is started by whoever first wrote in it. Null for a
     * session in which no message has an author yet.
     *
     * Messages are taken in `messageId` order, the order a session's messages are read in.
     */
    @GraphPath([
        Hop("HAS_MESSAGE", Direction.OUTGOING, label = "StoredMessage", orderBy = "messageId"),
        Hop("AUTHORED_BY", Direction.OUTGOING, label = "User"),
    ])
    val startedBy: StoredUser? = null,

    /** The session this one is part of; null for a top-level session. See [StoredSession.partOf]. */
    @GraphRelationship(type = "PART_OF", direction = Direction.OUTGOING)
    val partOf: SessionRef? = null,

    /** The one user this session is held with. See [StoredSession.heldWith]. */
    @GraphRelationship(type = "WITH", direction = Direction.OUTGOING)
    val heldWith: StoredUser? = null,
)
