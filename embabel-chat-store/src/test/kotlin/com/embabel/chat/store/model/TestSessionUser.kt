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
