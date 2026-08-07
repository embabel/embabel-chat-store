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

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.RangeIndex
import java.time.Instant

/**
 * Core session data stored as a Neo4j node.
 *
 * Sessions are identified by UUIDv7 which provides chronological ordering
 * when sorted lexicographically.
 */
@NodeFragment(labels = ["ChatSession"])
@RangeIndex(properties = ["lastActivityAt"])
data class SessionData(
    /**
     * Unique session identifier (UUIDv7 recommended).
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

    /** Last user-visible conversation activity; used for stable session-list ordering. */
    val lastActivityAt: Instant = createdAt,

    /**
     * Optional metadata for application-specific extensions.
     */
    val metadata: Map<String, Any>? = null
)
