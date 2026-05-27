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
package com.embabel.chat.store.autoconfigure

import com.embabel.agent.api.common.Ai
import com.embabel.chat.ConversationFactory
import com.embabel.chat.ConversationFactoryProvider
import com.embabel.chat.MapConversationFactoryProvider
import com.embabel.chat.store.adapter.LlmTitleGenerator
import com.embabel.chat.store.adapter.StoredConversationFactory
import com.embabel.chat.store.adapter.TitleGenerator
import com.embabel.chat.store.embedding.DefaultMessageEmbedder
import com.embabel.chat.store.embedding.MessageEmbedder
import com.embabel.chat.store.embedding.RoleFilteringMessageEmbedder
import com.embabel.chat.store.embedding.index.FalkorDbVectorIndexManager
import com.embabel.chat.store.embedding.index.MemgraphVectorIndexManager
import com.embabel.chat.store.embedding.index.Neo4jVectorIndexManager
import com.embabel.chat.store.embedding.index.SimilarityFunction
import com.embabel.chat.store.embedding.index.VectorIndexConfig
import com.embabel.chat.store.embedding.index.VectorIndexManager
import com.embabel.chat.store.event.SessionEventAwaiter
import com.embabel.chat.store.repository.ChatSessionRepository
import com.embabel.chat.support.InMemoryConversationFactory
import com.embabel.common.ai.model.EmbeddingService
import org.drivine.connection.DatabaseType
import org.drivine.manager.PersistenceManager
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean

/**
 * Auto-configuration for Embabel Chat Store.
 *
 * Automatically activates when:
 * - Chat store classes are on the classpath
 * - `embabel.chat.store.enabled=true` (default)
 *
 * This configuration provides:
 * - [StoredConversationFactory] - for persistent conversations
 * - [InMemoryConversationFactory] - for ephemeral conversations
 * - [ConversationFactoryProvider] - to access factories by type
 *
 * Apps can override by defining their own [ConversationFactory] beans.
 *
 * To disable entirely:
 * ```
 * embabel.chat.store.enabled=false
 * ```
 */
@AutoConfiguration
@ConditionalOnClass(ChatSessionRepository::class)
@ConditionalOnProperty(
    prefix = "embabel.chat.store",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true
)
@EnableConfigurationProperties(ChatStoreProperties::class)
open class ChatStoreAutoConfiguration {

    private val logger = LoggerFactory.getLogger(ChatStoreAutoConfiguration::class.java)

    /**
     * Creates an [LlmTitleGenerator] that uses [Ai] to generate session titles.
     *
     * Only created if:
     * - No existing [TitleGenerator] bean
     * - An [Ai] bean is available (from embabel-agent)
     *
     * Apps can override by defining their own [TitleGenerator] bean.
     */
    @Bean
    @ConditionalOnMissingBean(TitleGenerator::class)
    @ConditionalOnBean(Ai::class)
    open fun titleGenerator(ai: Ai): TitleGenerator = LlmTitleGenerator { prompt ->
        ai.withDefaultLlm().generateText(prompt)
    }

    /**
     * Creates a [MessageEmbedder] that uses [Ai] to compute a vector for each
     * persisted message, wrapped in a [RoleFilteringMessageEmbedder] so SYSTEM
     * messages and blank-content messages are skipped.
     *
     * Only created if:
     * - No existing [MessageEmbedder] bean
     * - An [Ai] bean is available (from embabel-agent)
     *
     * Apps can override by defining their own [MessageEmbedder] bean (for example,
     * to embed all roles, or to use a different embedding service per session).
     */
    @Bean
    @ConditionalOnMissingBean(MessageEmbedder::class)
    @ConditionalOnBean(Ai::class)
    open fun messageEmbedder(ai: Ai): MessageEmbedder = RoleFilteringMessageEmbedder(
        delegate = DefaultMessageEmbedder(ai.withDefaultEmbeddingService())
    )

    /**
     * Selects the [VectorIndexManager] implementation that matches the graph database
     * currently in use, based on Drivine's [PersistenceManager.type].
     *
     * Only created when a [PersistenceManager] is available. Apps can override by
     * defining their own [VectorIndexManager] bean.
     */
    @Bean
    @ConditionalOnMissingBean(VectorIndexManager::class)
    @ConditionalOnBean(PersistenceManager::class)
    open fun vectorIndexManager(persistenceManager: PersistenceManager): VectorIndexManager =
        when (persistenceManager.type) {
            DatabaseType.NEO4J -> Neo4jVectorIndexManager(persistenceManager)
            DatabaseType.MEMGRAPH -> MemgraphVectorIndexManager(persistenceManager)
            DatabaseType.FALKORDB -> FalkorDbVectorIndexManager(persistenceManager)
            else -> throw IllegalStateException(
                "No VectorIndexManager for Drivine DatabaseType ${persistenceManager.type}. " +
                    "Supported: NEO4J, MEMGRAPH, FALKORDB. Define your own VectorIndexManager " +
                    "bean to override."
            )
        }

    /**
     * Runs once at application startup: ensures the chat-message vector index exists,
     * sized to the configured embedding model's dimensions. Skipped when either the
     * vector index is disabled in config, no [Ai] bean is available, or no
     * [VectorIndexManager] is available.
     *
     * If an index already exists with a different shape (drift), the manager logs a
     * warning and does **not** auto-drop. Operators must call [VectorIndexManager.recreateIndex]
     * explicitly and re-embed existing messages — see the type's KDoc.
     */
    @Bean
    @ConditionalOnBean(value = [VectorIndexManager::class, Ai::class])
    @ConditionalOnProperty(
        prefix = "embabel.chat.store.vector-index",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    open fun vectorIndexEnsurer(
        vectorIndexManager: VectorIndexManager,
        ai: Ai,
        properties: ChatStoreProperties,
    ): ApplicationRunner = ApplicationRunner {
        val embeddingService = ai.withDefaultEmbeddingService()
        val config = VectorIndexConfig(
            label = properties.vectorIndex.label,
            property = properties.vectorIndex.property,
            dimensions = embeddingService.dimensions,
            similarityFunction = SimilarityFunction.valueOf(
                properties.vectorIndex.similarityFunction.uppercase()
            ),
            name = properties.vectorIndex.name,
        )
        logger.info("Ensuring vector index: {}", config)
        vectorIndexManager.ensureIndex(config)
    }

    /**
     * Creates a [StoredConversationFactory] for persistent conversations.
     *
     * Only created if:
     * - No existing [ConversationFactory] bean with qualifier "stored"
     * - [ChatSessionRepository] is available
     *
     * Optionally wires in:
     * - [TitleGenerator] - for auto-generating session titles
     * - [ApplicationEventPublisher] - for message lifecycle events
     */
    @Bean
    @ConditionalOnMissingBean(SessionEventAwaiter::class)
    open fun sessionEventAwaiter(): SessionEventAwaiter {
        return SessionEventAwaiter()
    }

    @Bean("storedConversationFactory")
    @ConditionalOnMissingBean(name = ["storedConversationFactory"])
    open fun storedConversationFactory(
        repository: ChatSessionRepository,
        sessionEventAwaiter: SessionEventAwaiter,
        properties: ChatStoreProperties,
        @Autowired(required = false) titleGenerator: TitleGenerator?,
        @Autowired(required = false) messageEmbedder: MessageEmbedder?,
        @Autowired(required = false) eventPublisher: ApplicationEventPublisher?
    ): ConversationFactory {
        logger.info(
            "Creating StoredConversationFactory (titleGenerator={}, messageEmbedder={}, titleAfterMessageCount={}, eventPublisher={})",
            titleGenerator?.javaClass?.simpleName ?: "none",
            messageEmbedder?.javaClass?.simpleName ?: "none",
            properties.titleAfterMessageCount,
            if (eventPublisher != null) "present" else "none"
        )

        return StoredConversationFactory(
            repository = repository,
            sessionEventAwaiter = sessionEventAwaiter,
            eventPublisher = eventPublisher,
            titleGenerator = titleGenerator,
            messageEmbedder = messageEmbedder,
            titleAfterMessageCount = properties.titleAfterMessageCount
        )
    }

    /**
     * Creates an [InMemoryConversationFactory] for ephemeral conversations.
     *
     * Useful for:
     * - Testing
     * - Short-lived sessions that don't need persistence
     * - Fallback when storage is unavailable
     */
    @Bean("inMemoryConversationFactory")
    @ConditionalOnMissingBean(name = ["inMemoryConversationFactory"])
    open fun inMemoryConversationFactory(
        @Autowired(required = false) eventPublisher: ApplicationEventPublisher?
    ): ConversationFactory {
        logger.info("Creating InMemoryConversationFactory")
        return InMemoryConversationFactory(eventPublisher)
    }

    /**
     * Creates a [ConversationFactoryProvider] that provides access to all registered factories.
     *
     * Factories are looked up by [com.embabel.chat.ConversationStoreType]:
     * - `STORED` -> storedConversationFactory
     * - `IN_MEMORY` -> inMemoryConversationFactory
     */
    @Bean
    @ConditionalOnMissingBean(ConversationFactoryProvider::class)
    open fun conversationFactoryProvider(
        factories: List<ConversationFactory>
    ): ConversationFactoryProvider {
        logger.info("Creating ConversationFactoryProvider with factories: {}",
            factories.map { it.storeType })
        return MapConversationFactoryProvider(factories)
    }
}