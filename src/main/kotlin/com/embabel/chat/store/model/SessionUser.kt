package com.embabel.chat.store.model

import com.embabel.chat.MessageAuthor
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId

/**
 * Interface for users that can own chat sessions and author messages.
 *
 * Extends [MessageAuthor] from embabel-agent-api with Drivine annotations
 * for Neo4j persistence. Applications implement this interface on their
 * user type to enable polymorphic session ownership.
 *
 * When using this library, you must register your concrete implementation
 * using `persistenceManager.registerSubtype()`:
 *
 * ```kotlin
 * @Bean
 * fun persistenceManager(factory: PersistenceManagerFactory): PersistenceManager {
 *     val pm = factory.get("neo")
 *     pm.registerSubtype(
 *         SessionUser::class.java,
 *         "MyUser|SessionUser",  // Composite label key (sorted alphabetically, pipe-separated)
 *         MyUserData::class.java
 *     )
 *     return pm
 * }
 * ```
 *
 * Example implementation:
 * ```kotlin
 * @NodeFragment(labels = ["SessionUser", "MyUser"])
 * data class MyUserData(
 *     @NodeId override val id: String,
 *     override val displayName: String,
 *     val email: String  // additional fields
 * ) : SessionUser
 * ```
 */
@NodeFragment(labels = ["SessionUser"])
interface SessionUser : MessageAuthor {
    /**
     * Unique identifier for the user.
     * Annotated with @NodeId so Drivine can identify nodes for change detection.
     */
    @get:NodeId
    override val id: String

    /**
     * Display name shown in UI contexts.
     */
    override val displayName: String
}
