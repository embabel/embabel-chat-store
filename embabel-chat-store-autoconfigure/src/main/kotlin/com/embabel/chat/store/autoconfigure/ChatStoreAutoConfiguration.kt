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
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.chat.ConversationFactory
import com.embabel.chat.ConversationFactoryProvider
import com.embabel.chat.MapConversationFactoryProvider
import com.embabel.chat.store.adapter.LlmTitleGenerator
import com.embabel.chat.store.adapter.StoredConversationFactory
import com.embabel.chat.store.adapter.TitleGenerator
import com.embabel.chat.store.embedding.DefaultMessageEmbedder
import com.embabel.chat.store.embedding.MessageEmbedder
import com.embabel.chat.store.embedding.RoleFilteringMessageEmbedder
import com.embabel.chat.store.event.SessionEventAwaiter
import com.embabel.chat.store.repository.ChatSessionRepository
import com.embabel.chat.store.repository.SessionActivityMigration
import com.embabel.chat.store.repository.SessionOrder
import com.embabel.chat.support.InMemoryConversationFactory
import org.drivine.manager.PersistenceManager
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaCatalog
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
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
     *
     * The embedding service is resolved per call rather than here, so that a host with no
     * embedding model configured yet still gets a context — see [LazyEmbeddingService].
     */
    @Bean
    @ConditionalOnMissingBean(MessageEmbedder::class)
    @ConditionalOnBean(Ai::class)
    open fun messageEmbedder(
        ai: Ai,
        embeddingServices: ObjectProvider<EmbeddingService>,
    ): MessageEmbedder = RoleFilteringMessageEmbedder(
        delegate = DefaultMessageEmbedder(LazyEmbeddingService { embeddingService(ai, embeddingServices) })
    )

    /**
     * The application's own [EmbeddingService] bean where there is an unambiguous one
     * (a `@Primary` bean counts), otherwise the platform default.
     *
     * Preferring the bean matters for a host that can start with NO embedding model
     * configured — one whose provider key arrives at first run rather than at boot. Such a
     * host registers an embedding service that reports its own absence and can be switched
     * on later, whereas `ai.withDefaultEmbeddingService()` resolves the default eagerly and
     * throws when no model is registered, taking the application context down with it.
     */
    private fun embeddingService(ai: Ai, embeddingServices: ObjectProvider<EmbeddingService>): EmbeddingService =
        embeddingServices.getIfUnique() ?: ai.withDefaultEmbeddingService()

    /**
     * Declares the chat-store uniqueness constraints — one per node-identity property.
     * Drivine's [org.drivine.schema.SchemaManager] (registered by the Drivine starter)
     * ensures every [SchemaCatalog] bean idempotently on startup, so this needs no
     * runner of its own. Enforcement is governed by `drivine.schema.enabled` (default true).
     *
     * Owned separately from the vector catalog: catalogs sharing an owner are merged, and
     * their versions with them, so an unowned constraint catalog would be versioned by the
     * embedding model and would drag the model version to null whenever the vector catalog
     * is skipped.
     */
    @Bean
    open fun chatStoreConstraintSchema(): SchemaCatalog = SchemaCatalog.of(
        UniquenessConstraintSpec(label = "ChatSession", property = "sessionId"),
        UniquenessConstraintSpec(label = "StoredMessage", property = "messageId"),
        UniquenessConstraintSpec(label = "User", property = "id"),
        UniquenessConstraintSpec(label = "Attachment", property = "attachmentId"),
        RangeIndexSpec(label = "ChatSession", property = "lastActivityAt"),
    ).named(CONSTRAINT_SCHEMA_OWNER)

    /**
     * Backfills activity for installations that predate most-recently-active ordering.
     *
     * Resolved through an [ObjectProvider] rather than `@ConditionalOnBean`: the condition is
     * evaluated at auto-configuration parse time, so an application that registers its
     * [PersistenceManager] from another auto-configuration or a `BeanFactoryPostProcessor`
     * would silently get no runner — and un-backfilled sessions then vanish from every page
     * after the first under [SessionOrder.LAST_ACTIVITY]. Absence is logged loudly instead.
     */
    @Bean
    open fun chatStoreSessionActivityMigration(
        persistenceManagers: ObjectProvider<PersistenceManager>,
    ): ApplicationRunner = ApplicationRunner {
        val persistenceManager = persistenceManagers.getIfAvailable()
        if (persistenceManager == null) {
            logger.warn(
                "No PersistenceManager bean: skipping the session activity backfill. Sessions " +
                    "created before lastActivityAt existed will be missing from paged listings " +
                    "ordered by ${SessionOrder.LAST_ACTIVITY}."
            )
        } else {
            SessionActivityMigration(persistenceManager).migrate()
        }
    }

    /**
     * Declares the chat-message vector index, sized to the configured embedding model's
     * dimensions and tagged with the model name as a schema version. A later model swap
     * (same dimensions) therefore triggers a one-time index rebuild — re-embed existing
     * messages first; see [com.embabel.chat.store.model.MessageData.embeddingModel].
     *
     * Drivine's SchemaManager ensures it idempotently on startup: an already-matching
     * index is left untouched, and a shape change (e.g. different dimensions) is reported
     * as drift rather than silently dropped. Skipped when no [Ai] bean is available or the
     * vector index is disabled via `embabel.chat.store.vector-index.enabled=false`.
     */
    @Bean
    @ConditionalOnBean(Ai::class)
    @ConditionalOnProperty(
        prefix = "embabel.chat.store.vector-index",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    open fun chatStoreVectorIndexSchema(
        ai: Ai,
        properties: ChatStoreProperties,
        embeddingServices: ObjectProvider<EmbeddingService>,
    ): SchemaCatalog {
        // A vector index is created AT the embedding model's dimension, so with no model
        // there is no dimension to create it at. Register nothing rather than guess: an
        // index at the wrong dimension is worse than none, because writes to it succeed.
        // The catalog is rebuilt on the next boot, by which time a model configured at
        // first run is registered. An absent-tolerant service may signal absence either by
        // throwing or by reporting no dimensions, so both are treated as "no model".
        val (dimensions, modelName) = runCatching {
            val es = embeddingService(ai, embeddingServices)
            val dimensions = es.dimensions
            require(dimensions > 0) { "embedding service reports $dimensions dimensions" }
            dimensions to es.name
        }.getOrElse {
            logger.warn("Skipping chat-message vector index schema: no embedding model ({})", it.message, it)
            return SchemaCatalog.of().named(VECTOR_SCHEMA_OWNER)
        }
        val vi = properties.vectorIndex
        val spec = VectorIndexSpec(
            label = vi.label,
            property = vi.property,
            dimensions = dimensions,
            similarity = SimilarityFunction.valueOf(vi.similarityFunction.uppercase()),
            name = vi.name,
        )
        logger.info("Registering chat-message vector index schema: {} (model={})", spec, modelName)
        return SchemaCatalog.of(spec).named(VECTOR_SCHEMA_OWNER).withVersion(modelName)
    }

    companion object {
        /** Drivine schema owner for the chat-store constraints. */
        const val CONSTRAINT_SCHEMA_OWNER = "embabel-chat-store"

        /** Drivine schema owner for the chat-message vector index, versioned by embedding model. */
        const val VECTOR_SCHEMA_OWNER = "embabel-chat-store-vector"
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
