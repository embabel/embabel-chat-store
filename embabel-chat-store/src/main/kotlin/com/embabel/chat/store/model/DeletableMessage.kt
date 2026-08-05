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
 * Delete-only view of a message: the `:StoredMessage` node plus the `:Attachment` nodes
 * that hang off it, and nothing else.
 *
 * Exists because the cascade boundary IS the view shape. [DeletableSession] previously
 * declared its children as bare [MessageData], which was correct while a message owned no
 * nodes of its own — DETACH DELETE dropped its AUTHORED_BY/SENT_TO edges and left every
 * `:User` standing. Once messages could own attachments, that same narrowness silently
 * orphaned them: the edge went, the `:Attachment` node stayed, and it pointed at stored
 * bytes nothing could reach any more.
 *
 * Still deliberately narrow. AUTHORED_BY and SENT_TO are omitted, so the cascade
 * structurally cannot reach `:User` — the safety is the shape, not a flag.
 *
 * Neo4j structure traversed:
 * ```
 * (msg:StoredMessage)-[:HAS_ATTACHMENT]->(att:Attachment)
 * ```
 *
 * @see DeletableSession for the session-level cascade this composes into
 */
@GraphView
data class DeletableMessage(
    /**
     * The message node (id = messageId).
     */
    @Root val message: MessageData,

    /**
     * The message's attachments, deleted with it.
     */
    @GraphRelationship(type = "HAS_ATTACHMENT", direction = Direction.OUTGOING)
    val attachments: List<AttachmentData> = emptyList()
)
