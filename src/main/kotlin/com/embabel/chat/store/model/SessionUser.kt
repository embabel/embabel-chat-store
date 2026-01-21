package com.embabel.chat.store.model

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId

/**
 * Interface for users that can own chat sessions.
 *
 * Applications implement this interface on their user type to enable
 * polymorphic session ownership. The Drivine @NodeFragment annotation
 * with the "SessionUser" label allows the library to work with any
 * user type that implements this interface.
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
interface SessionUser {
    /**
     * Unique identifier for the user.
     * Annotated with @NodeId so Drivine can identify nodes for change detection.
     */
    @get:NodeId
    val id: String

    /**
     * Display name shown in UI contexts.
     */
    val displayName: String
}
