package com.embabel.chat.store.model

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId

/**
 * Test implementation of SessionUser for integration tests.
 *
 * Demonstrates how applications implement the SessionUser interface
 * with their own user type and additional labels.
 */
@NodeFragment(labels = ["SessionUser", "TestUser"])
data class TestSessionUser(
    @NodeId override val id: String,
    override val displayName: String
) : SessionUser