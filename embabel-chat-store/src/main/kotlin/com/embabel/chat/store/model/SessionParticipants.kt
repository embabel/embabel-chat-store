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
