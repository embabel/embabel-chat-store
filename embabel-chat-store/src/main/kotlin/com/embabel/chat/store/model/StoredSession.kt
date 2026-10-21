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
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.Root
import org.drivine.annotation.SortedBy

/**
 * Complete session view including owner and messages.
 *
 * This is a Drivine GraphView that aggregates:
 * - The session data (root node)
 * - The owning user (polymorphic via [StoredUser] interface)
 * - All messages in the session (sorted by messageId for chronological order)
 *
 * Example Neo4j structure:
 * ```
 * (session:ChatSession)-[:OWNED_BY]->(user:User)
 * (session:ChatSession)-[:HAS_MESSAGE]->(msg:StoredMessage)
 * (session:ChatSession)-[:PART_OF]->(thread:ChatSession)   // optional
 * (session:ChatSession)-[:WITH]->(agent:User)              // optional
 * ```
 */
@GraphView
data class StoredSession(
    /**
     * The session node data.
     */
    @Root val session: SessionData,

    /**
     * The user who owns this session.
     * Polymorphic - can be any type implementing [StoredUser].
     */
    @GraphRelationship(type = "OWNED_BY", direction = Direction.OUTGOING)
    val owner: StoredUser,

    /**
     * Messages in this session, sorted by messageId (UUIDv7 = chronological order).
     */
    @GraphRelationship(type = "HAS_MESSAGE", direction = Direction.OUTGOING)
    @SortedBy("message.messageId")
    val messages: List<SimpleStoredMessage> = emptyList(),

    /**
     * The session this one is part of — an agent's private conversation inside a thread, say.
     * Null for a top-level session. Set when the session is created and never changed.
     */
    @GraphRelationship(type = "PART_OF", direction = Direction.OUTGOING)
    val partOf: SessionRef? = null,

    /**
     * The one user this session is held with, such as the agent a side conversation is with.
     * Set when the session is created and never changed.
     */
    @GraphRelationship(type = "WITH", direction = Direction.OUTGOING)
    val heldWith: StoredUser? = null,
) {
    /**
     * Returns a copy of this session with an additional message.
     */
    fun withMessage(message: SimpleStoredMessage): StoredSession =
        copy(messages = messages + message)
}

/**
 * Lightweight session reference.
 * Contains only the session ID — used as the root of [NewMessageInSession] to create
 * the HAS_MESSAGE relationship without loading or modifying session properties, and as the
 * target of a session's `PART_OF` ([StoredSession.partOf], [SessionSummary.partOf]), so saving
 * a part writes the relationship and leaves the session it is part of as it was.
 *
 * @see NewMessageInSession
 */
@NodeFragment(labels = ["ChatSession"])
data class SessionRef(
    @NodeId val sessionId: String
)
