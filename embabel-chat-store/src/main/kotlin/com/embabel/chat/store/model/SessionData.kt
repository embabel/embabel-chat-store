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

import org.drivine.annotation.Default
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.NodeStamp
import org.drivine.annotation.RangeIndex
import java.time.Instant

/**
 * Core session data stored as a Neo4j node.
 *
 * Two orderings are supported over these properties, selected by
 * [com.embabel.chat.store.repository.SessionOrder]: creation order via [sessionId], and
 * most-recently-active order via [lastActivityAt] with [sessionId] as tie-breaker.
 */
@NodeFragment(labels = ["ChatSession"])
@RangeIndex(properties = ["lastActivityAt"])
@RangeIndex(properties = ["kind"])
data class SessionData(
    /**
     * Unique session identifier. UUIDv7 is strongly recommended: it embeds its creation
     * timestamp in the leading bits, so lexicographic order is creation order, which is what
     * [com.embabel.chat.store.repository.SessionOrder.CREATED] paginates on. Any unique
     * string still paginates deterministically, just not chronologically.
     *
     * Don't encode meaning in the ID, such as a prefix for a kind of session: a prefixed ID is
     * no longer time-ordered, and what it says can't change without changing the ID. Say what a
     * session is with [kind], and which session it belongs to with `PART_OF`.
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
     * Last user-visible conversation activity, advanced only when a message is added —
     * enrichment such as narration deliberately does not reorder sessions.
     *
     * [Default] makes a session stored before this property existed hydrate as [createdAt]
     * rather than failing to load, so old data stays readable and orders sensibly under
     * [com.embabel.chat.store.repository.SessionOrder.CREATED] with no migration at all.
     * [com.embabel.chat.store.repository.SessionOrder.LAST_ACTIVITY] additionally needs the
     * value materialised in the graph — a null never satisfies a keyset comparison. Adding a
     * message writes it.
     */
    @Default
    val lastActivityAt: Instant = createdAt,

    /**
     * Optional metadata for application-specific extensions.
     */
    val metadata: Map<String, Any>? = null,

    /**
     * Drivine's stamp of the session node, filled when the session is loaded. A save of a session
     * that carries one is refused with `StaleObjectException` when the node's properties changed
     * after the load. Treat it as opaque, and leave it null for a new session.
     */
    @NodeStamp val stamp: String? = null,

    /**
     * What kind of session this is — `chat`, `thread`, or any name the app chooses. It is what an
     * app tells its sessions apart by, so the session ID never has to: keep the ID an opaque UUIDv7.
     * Set when the session is created and never changed.
     *
     * [Default] makes a session stored before this property existed hydrate as [DEFAULT_KIND], and
     * a filter for [DEFAULT_KIND] matches it too. To write the value onto such sessions, run
     * [com.embabel.chat.store.repository.ChatSessionRepository.backfillSessionKinds].
     */
    @Default
    val kind: String = DEFAULT_KIND,
) {
    companion object {
        /** The kind of a session created without one, and of every session stored before kinds existed. */
        const val DEFAULT_KIND = "chat"
    }
}
