package com.embabel.chat.store.model

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId

/**
 * Test implementation of [StoredUser] for integration tests.
 *
 * Demonstrates how applications implement the StoredUser interface
 * with their own user type and additional labels for Drivine polymorphism.
 */
@NodeFragment(labels = ["User", "TestUser"])
data class TestSessionUser(
    @NodeId override val id: String,
    override val displayName: String,
    override val username: String = displayName,
    override val email: String? = null
) : StoredUser