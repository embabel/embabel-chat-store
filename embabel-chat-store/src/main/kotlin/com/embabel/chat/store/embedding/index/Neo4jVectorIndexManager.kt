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
package com.embabel.chat.store.embedding.index

import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.slf4j.LoggerFactory

/**
 * [VectorIndexManager] for Neo4j 5.13+.
 *
 * Uses native vector index DDL (`CREATE VECTOR INDEX … IF NOT EXISTS`) and introspects
 * via `SHOW VECTOR INDEXES`. Drift is detected by reading the existing index's
 * `options.indexConfig` and comparing dimensions and similarity function.
 *
 * Neo4j 5.11/5.12 had a preview API via `db.index.vector.createNodeIndex` — this
 * implementation requires the GA DDL and therefore Neo4j 5.13+.
 */
class Neo4jVectorIndexManager(
    private val persistenceManager: PersistenceManager,
) : VectorIndexManager {

    private val logger = LoggerFactory.getLogger(Neo4jVectorIndexManager::class.java)

    override fun ensureIndex(config: VectorIndexConfig): VectorIndexManager.EnsureResult {
        val existing = findIndex(config.label, config.property)
        if (existing == null) {
            createIndex(config)
            val created = findIndex(config.label, config.property)
                ?: error("Neo4j reported success creating vector index but introspection cannot find it: ${config.effectiveName}")
            logger.info("Created Neo4j vector index '{}' on {}({}) — {} dims, {}",
                created.name, created.label, created.property,
                created.dimensions, created.similarityFunction)
            return VectorIndexManager.EnsureResult.Created(created)
        }
        return if (existing.matches(config)) {
            logger.debug("Neo4j vector index already matches requested config: {}", existing.name)
            VectorIndexManager.EnsureResult.AlreadyMatching(existing)
        } else {
            logger.warn(
                "Neo4j vector index '{}' on {}({}) does not match requested config: " +
                    "existing dims={}/similarity={}, requested dims={}/similarity={}. " +
                    "Call recreateIndex() and re-embed existing messages to resolve.",
                existing.name, existing.label, existing.property,
                existing.dimensions, existing.similarityFunction,
                config.dimensions, config.similarityFunction,
            )
            VectorIndexManager.EnsureResult.Drift(existing, config)
        }
    }

    override fun recreateIndex(config: VectorIndexConfig) {
        dropIndex(config.label, config.property)
        createIndex(config)
        logger.warn(
            "Recreated Neo4j vector index on {}({}) with {} dims, {} — " +
                "previously-stored vectors are now stale and must be re-embedded.",
            config.label, config.property, config.dimensions, config.similarityFunction,
        )
    }

    override fun findIndex(label: String, property: String): VectorIndexInfo? {
        val rows = persistenceManager.query(
            QuerySpecification.withStatement(SHOW_VECTOR_INDEXES).transform(Map::class.java)
        )
        @Suppress("UNCHECKED_CAST")
        return rows.asSequence()
            .map { it as Map<String, Any?> }
            .firstOrNull { row ->
                @Suppress("UNCHECKED_CAST")
                val labelsOrTypes = row["labelsOrTypes"] as? List<String> ?: return@firstOrNull false
                @Suppress("UNCHECKED_CAST")
                val properties = row["properties"] as? List<String> ?: return@firstOrNull false
                label in labelsOrTypes && property in properties
            }
            ?.let { it.toVectorIndexInfo() }
    }

    override fun dropIndex(label: String, property: String) {
        val existing = findIndex(label, property) ?: return
        val name = existing.name
            ?: error("Neo4j vector index found without a name — this shouldn't happen on Neo4j: $existing")
        persistenceManager.execute(QuerySpecification.withStatement("DROP INDEX `$name` IF EXISTS"))
        logger.info("Dropped Neo4j vector index '{}'", name)
    }

    private fun createIndex(config: VectorIndexConfig) {
        val similarity = config.similarityFunction.name.lowercase()
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                CREATE VECTOR INDEX `${config.effectiveName}` IF NOT EXISTS
                FOR (n:${config.label}) ON (n.${config.property})
                OPTIONS { indexConfig: {
                  `vector.dimensions`: ${config.dimensions},
                  `vector.similarity_function`: '$similarity'
                }}
                """.trimIndent()
            )
        )
    }

    private fun Map<String, Any?>.toVectorIndexInfo(): VectorIndexInfo {
        @Suppress("UNCHECKED_CAST")
        val options = this["options"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val indexConfig = options?.get("indexConfig") as? Map<String, Any?>
        val dimensions = (indexConfig?.get("vector.dimensions") as? Number)?.toInt()
            ?: error("Neo4j vector index missing 'vector.dimensions' in options.indexConfig: $this")
        val similarityName = (indexConfig["vector.similarity_function"] as? String)
            ?: error("Neo4j vector index missing 'vector.similarity_function' in options.indexConfig: $this")
        @Suppress("UNCHECKED_CAST")
        val labels = this["labelsOrTypes"] as List<String>
        @Suppress("UNCHECKED_CAST")
        val properties = this["properties"] as List<String>
        return VectorIndexInfo(
            name = this["name"] as String?,
            label = labels.first(),
            property = properties.first(),
            dimensions = dimensions,
            similarityFunction = SimilarityFunction.valueOf(similarityName.uppercase()),
        )
    }

    companion object {
        private const val SHOW_VECTOR_INDEXES =
            """SHOW VECTOR INDEXES
               YIELD name, labelsOrTypes, properties, options
               RETURN {name: name, labelsOrTypes: labelsOrTypes,
                       properties: properties, options: options} AS row"""
    }
}