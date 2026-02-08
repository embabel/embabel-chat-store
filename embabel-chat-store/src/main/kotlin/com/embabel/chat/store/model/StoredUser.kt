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

import com.embabel.agent.api.identity.User
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId

/**
 * A [User] that can be persisted to a graph database via Drivine.
 *
 * This interface extends [User] with Drivine annotations for Neo4j persistence.
 * It exists so that agent-api's [User] interface remains persistence-agnostic
 * while chat-store can specify the graph labels needed for polymorphic loading.
 *
 * ## Why StoredUser exists
 *
 * Drivine needs `@NodeFragment` annotations to know which labels to query when
 * loading relationships. The base [User] interface from agent-api is persistence-
 * agnostic and doesn't have these annotations. This interface bridges the gap.
 *
 * ## Implementation
 *
 * Applications should implement this interface for their user type:
 *
 * ```kotlin
 * @NodeFragment(labels = ["User", "MyUser"])
 * data class MyUser(
 *     @NodeId override val id: String,
 *     override val displayName: String,
 *     override val username: String,
 *     override val email: String?
 * ) : StoredUser
 * ```
 *
 * Then register with Drivine:
 *
 * ```kotlin
 * persistenceManager.registerSubtype(
 *     StoredUser::class.java,
 *     "MyUser|User",  // Labels sorted alphabetically
 *     MyUser::class.java
 * )
 * ```
 *
 * For simple cases, use [SimpleStoredUser] directly.
 */
@NodeFragment(labels = ["User"])
interface StoredUser : User {
    @get:NodeId
    override val id: String
}

/**
 * Ready-to-use implementation of [StoredUser] for applications that don't need
 * additional fields beyond what [User] provides.
 *
 * ## Usage
 *
 * ```kotlin
 * val user = SimpleStoredUser(
 *     id = "user-123",
 *     displayName = "Alice",
 *     username = "alice",
 *     email = "alice@example.com"
 * )
 * chatSessionRepository.createSession(sessionId, user, "My Chat")
 * ```
 *
 * ## Registration
 *
 * Register with Drivine for polymorphic loading:
 *
 * ```kotlin
 * persistenceManager.registerSubtype(
 *     StoredUser::class.java,
 *     "SimpleStoredUser|User",
 *     SimpleStoredUser::class.java
 * )
 * ```
 */
@NodeFragment(labels = ["User", "SimpleStoredUser"])
data class SimpleStoredUser(
    @NodeId override val id: String,
    override val displayName: String,
    override val username: String,
    override val email: String?
) : StoredUser