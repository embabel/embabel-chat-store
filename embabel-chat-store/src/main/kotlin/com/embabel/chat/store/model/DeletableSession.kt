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
 * the session, its messages and the sessions that are part of it, and nothing else.
 *
 * Deliberately narrow:
 * - The child is [DeletableMessage], which carries the `:StoredMessage` node and its
 *   HAS_ATTACHMENT, HAS_ASSET and HAS_DATA_ATTACHMENT children — but **not** [SimpleStoredMessage] (which carries
 *   AUTHORED_BY/SENT_TO → [StoredUser]). A DETACH DELETE of each message node drops
 *   those edges while leaving the `:User` nodes intact.
 * - The OWNED_BY owner and the WITH user are omitted entirely. Because the view declares only
 *   HAS_MESSAGE and PART_OF, the cascade physically cannot reach `:User` — the safety is
 *   structural, not a flag.
 *
 * Attachments and asset metadata are included precisely because that structural narrowness
 * cuts both ways: they are owned by the message and reachable from nowhere else.
 *
 * The sessions that are `PART_OF` this one go with it, through [parts]: this view again, so each
 * takes its own messages with it. A session can be part of a top-level session only, so [parts]
 * reaches one level. Drivine refuses, before writing anything, a cascade that would leave a deeper
 * level behind, so were that rule ever broken the delete would fail rather than orphan messages.
 *
 * Neo4j structure traversed:
 * ```
 * (session:ChatSession)-[:HAS_MESSAGE]->(msg:StoredMessage)-[:HAS_ATTACHMENT]->(att:Attachment)
 *                                                    \-[:HAS_ASSET]->(asset:StoredAsset)
 *                                                    \-[:HAS_DATA_ATTACHMENT]->(data:DataAttachment)
 * (session:ChatSession)<-[:PART_OF]-(part:ChatSession)-[:HAS_MESSAGE]->(msg:StoredMessage)…
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
    val messages: List<DeletableMessage> = emptyList(),

    /**
     * The sessions that are part of this one, with their messages.
     */
    @GraphRelationship(type = "PART_OF", direction = Direction.INCOMING, maxDepth = 1)
    val parts: List<DeletableSession> = emptyList(),
)
