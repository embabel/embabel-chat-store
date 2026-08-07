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
import com.embabel.chat.store.repository.ChatSessionRepository
import com.embabel.common.ai.model.EmbeddingService
import org.assertj.core.api.Assertions.assertThat
import org.drivine.schema.SchemaCatalog
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * Verifies the auto-configuration *wiring* of the Drivine schema catalogs — that the right
 * specs are declared and that the conditional switches behave — without needing a database.
 * The end-to-end "schema is actually created/enforced" path is covered by
 * `SchemaManagementIntegrationTest` in the core module.
 */
class ChatStoreSchemaWiringTest {

    private val embeddingService = mock<EmbeddingService> {
        on { dimensions } doReturn 1536
        on { name } doReturn "stub-embed-model"
    }

    private val ai = mock<Ai> {
        on { withDefaultEmbeddingService() } doReturn embeddingService
    }

    // ChatSessionRepository is required by storedConversationFactory; the schema beans
    // don't use it, but the context needs one to start.
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ChatStoreAutoConfiguration::class.java))
        .withBean(ChatSessionRepository::class.java, { mock<ChatSessionRepository>() })

    @Test
    fun `registers both the constraint and vector schema catalogs when an Ai is present`() {
        runner.withBean(Ai::class.java, { ai }).run { context ->
            val catalogs = context.getBeansOfType(SchemaCatalog::class.java).values
            assertThat(catalogs).hasSize(2)

            val constraintCatalog = context.getBean("chatStoreConstraintSchema", SchemaCatalog::class.java)
            assertThat(constraintCatalog.constraints.map { (it as UniquenessConstraintSpec).label })
                .containsExactlyInAnyOrder("ChatSession", "StoredMessage", "User", "Attachment")
            assertThat(constraintCatalog.indexes).containsExactly(
                RangeIndexSpec("ChatSession", "lastActivityAt")
            )

            val vectorCatalog = context.getBean("chatStoreVectorIndexSchema", SchemaCatalog::class.java)
            val spec = vectorCatalog.indexes.single() as VectorIndexSpec
            assertThat(spec.label).isEqualTo("StoredMessage")
            assertThat(spec.property).isEqualTo("embedding")
            assertThat(spec.dimensions).isEqualTo(1536)
            // The embedding model id becomes the catalog version → model swap triggers rebuild.
            assertThat(vectorCatalog.version).isEqualTo("stub-embed-model")
        }
    }

    @Test
    fun `omits the vector schema catalog when the vector index is disabled`() {
        runner.withBean(Ai::class.java, { ai })
            .withPropertyValues("embabel.chat.store.vector-index.enabled=false")
            .run { context ->
                val catalogs = context.getBeansOfType(SchemaCatalog::class.java).values
                assertThat(catalogs).hasSize(1)
                assertThat(catalogs.single().constraints).hasSize(4)
                assertThat(catalogs.single().indexes).containsExactly(
                    RangeIndexSpec("ChatSession", "lastActivityAt")
                )
            }
    }

    @Test
    fun `still declares constraints but no vector index when no Ai bean is present`() {
        runner.run { context ->
            val catalogs = context.getBeansOfType(SchemaCatalog::class.java).values
            assertThat(catalogs).hasSize(1)
            assertThat(catalogs.single().constraints).hasSize(4)
            assertThat(catalogs.single().indexes).containsExactly(
                RangeIndexSpec("ChatSession", "lastActivityAt")
            )
        }
    }
}
