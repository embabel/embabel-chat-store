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
 * - The child is [DeletableMessage], which carries the `:StoredMessage` node and its
 *   HAS_ATTACHMENT children — but **not** [SimpleStoredMessage] (which carries
 *   AUTHORED_BY/SENT_TO → [StoredUser]). A DETACH DELETE of each message node drops
 *   those edges while leaving the `:User` nodes intact.
 * - The OWNED_BY owner is omitted entirely. Because the view declares only
 *   HAS_MESSAGE, the cascade physically cannot reach `:User` — the safety is
 *   structural, not a flag.
 *
 * Attachments are included precisely because that structural narrowness cuts both ways:
 * a message's attachments are owned by the message and reachable from nowhere else, so a
 * cascade that stops at `:StoredMessage` leaves `:Attachment` nodes orphaned, referencing
 * stored bytes nothing can reach.
 *
 * Neo4j structure traversed:
 * ```
 * (session:ChatSession)-[:HAS_MESSAGE]->(msg:StoredMessage)-[:HAS_ATTACHMENT]->(att:Attachment)
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
     * The session's messages, with their attachments.
     */
    @GraphRelationship(type = "HAS_MESSAGE", direction = Direction.OUTGOING)
    val messages: List<DeletableMessage> = emptyList()
)
