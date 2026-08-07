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
package com.embabel.chat.store

import com.embabel.chat.store.model.StoredUser
import com.embabel.chat.store.model.TestSessionUser
import com.embabel.chat.store.repository.ChatSessionRepository
import com.embabel.chat.store.repository.ChatSessionRepositoryImpl
import org.drivine.autoconfigure.EnableDrivine
import org.drivine.autoconfigure.EnableDrivineTestConfig
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.GraphObjectManagerFactory
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.EnableAspectJAutoProxy

/**
 * Test application configuration for embabel-chat-store tests.
 *
 * Uses Drivine's test support which automatically configures:
 * - Neo4j testcontainer (or local Neo4j if USE_LOCAL_NEO4J=true)
 * - Transaction management
 *
 * Demonstrates the polymorphic StoredUser registration that library
 * consumers must perform for their user implementation.
 */
@Configuration
@EnableDrivine
@EnableDrivineTestConfig
@ComponentScan(basePackages = ["com.embabel.chat.store"])
@EnableAspectJAutoProxy(proxyTargetClass = true)
class TestApplication {

    @Bean
    fun persistenceManager(factory: PersistenceManagerFactory): PersistenceManager {
        val pm = factory.get("neo")
        // Register TestSessionUser as implementation of StoredUser interface.
        // Library consumers must do this for their own user type.
        pm.registerSubtype(
            StoredUser::class.java,
            listOf("TestUser", "User"),
            TestSessionUser::class.java
        )
        return pm
    }

    @Bean
    fun graphObjectManager(factory: GraphObjectManagerFactory): GraphObjectManager {
        return factory.get("neo")
    }

    @Bean
    fun chatSessionRepository(
        graphObjectManager: GraphObjectManager,
        persistenceManager: PersistenceManager,
    ): ChatSessionRepository {
        return ChatSessionRepositoryImpl(graphObjectManager, persistenceManager)
    }
}
