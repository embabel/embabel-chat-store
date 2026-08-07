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
data class SessionData(
    /**
     * Unique session identifier. UUIDv7 is strongly recommended: it embeds its creation
     * timestamp in the leading bits, so lexicographic order is creation order, which is what
     * [com.embabel.chat.store.repository.SessionOrder.CREATED] paginates on. Any unique
     * string still paginates deterministically, just not chronologically.
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
     * value materialised in the graph — a null never satisfies a keyset comparison — so
     * [com.embabel.chat.store.repository.SessionActivityMigration] still backfills it, but as
     * a correctness step for that one ordering rather than a prerequisite for reading at all.
     */
    @Default
    val lastActivityAt: Instant = createdAt,

    /**
     * Optional metadata for application-specific extensions.
     */
    val metadata: Map<String, Any>? = null
)
