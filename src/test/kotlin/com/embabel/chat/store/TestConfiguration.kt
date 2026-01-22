package com.embabel.chat.store

import com.embabel.chat.store.model.SessionUser
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
 * Demonstrates the polymorphic SessionUser registration that library
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
        // Register TestSessionUser as implementation of SessionUser interface.
        // Library consumers must do this for their own user type.
        pm.registerSubtype(
            SessionUser::class.java,
            "SessionUser|TestUser",  // Composite label key (sorted alphabetically)
            TestSessionUser::class.java
        )
        return pm
    }

    @Bean
    fun graphObjectManager(factory: GraphObjectManagerFactory): GraphObjectManager {
        return factory.get("neo")
    }

    @Bean
    fun chatSessionRepository(graphObjectManager: GraphObjectManager): ChatSessionRepository {
        return ChatSessionRepositoryImpl(graphObjectManager)
    }
}