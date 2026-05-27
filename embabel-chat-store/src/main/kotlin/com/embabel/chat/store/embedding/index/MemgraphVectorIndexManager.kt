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
 * [VectorIndexManager] for Memgraph 2.x.
 *
 * Memgraph's vector-index DDL uses `WITH CONFIG {…}` rather than Neo4j's
 * `OPTIONS { indexConfig: {…} }`, the dimension key is `dimension` (singular), and the
 * similarity argument is `metric` taking uSearch shorthand values (`cos`, `l2sq`, `ip`).
 * Memgraph also lacks `IF NOT EXISTS` on `CREATE VECTOR INDEX`, so existence is checked
 * via `vector_search.show_index_info()` before issuing the create.
 *
 * @param capacity the initial Memgraph index capacity; Memgraph grows the index from
 *   here, so over-provisioning slightly is cheaper than under-provisioning.
 */
class MemgraphVectorIndexManager(
    private val persistenceManager: PersistenceManager,
    private val capacity: Int = DEFAULT_CAPACITY,
) : VectorIndexManager {

    private val logger = LoggerFactory.getLogger(MemgraphVectorIndexManager::class.java)

    override fun ensureIndex(config: VectorIndexConfig): VectorIndexManager.EnsureResult {
        val existing = findIndex(config.label, config.property)
        if (existing == null) {
            createIndex(config)
            val created = findIndex(config.label, config.property)
                ?: error("Memgraph reported success creating vector index but introspection cannot find it: ${config.effectiveName}")
            logger.info("Created Memgraph vector index '{}' on {}({}) — {} dims, {}",
                created.name, created.label, created.property,
                created.dimensions, created.similarityFunction)
            return VectorIndexManager.EnsureResult.Created(created)
        }
        return if (existing.matches(config)) {
            logger.debug("Memgraph vector index already matches requested config: {}", existing.name)
            VectorIndexManager.EnsureResult.AlreadyMatching(existing)
        } else {
            logger.warn(
                "Memgraph vector index '{}' on {}({}) does not match requested config: " +
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
            "Recreated Memgraph vector index on {}({}) with {} dims, {} — " +
                "previously-stored vectors are now stale and must be re-embedded.",
            config.label, config.property, config.dimensions, config.similarityFunction,
        )
    }

    override fun findIndex(label: String, property: String): VectorIndexInfo? {
        val rows = try {
            persistenceManager.query(
                QuerySpecification
                    .withStatement(SHOW_VECTOR_INDEXES)
                    .transform(Map::class.java)
            )
        } catch (e: Exception) {
            logger.debug("Could not query Memgraph vector indexes (probably none exist): {}", e.message)
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return rows.asSequence()
            .map { it as Map<String, Any?> }
            .firstOrNull { row ->
                row["label"]?.toString() == label && row["property"]?.toString() == property
            }
            ?.let { it.toVectorIndexInfo() }
    }

    override fun dropIndex(label: String, property: String) {
        val existing = findIndex(label, property) ?: return
        val name = existing.name
            ?: error("Memgraph vector index found without a name — this shouldn't happen on Memgraph: $existing")
        persistenceManager.execute(QuerySpecification.withStatement("DROP VECTOR INDEX $name"))
        logger.info("Dropped Memgraph vector index '{}'", name)
    }

    private fun createIndex(config: VectorIndexConfig) {
        val metric = config.similarityFunction.toMemgraphMetric()
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                CREATE VECTOR INDEX ${config.effectiveName} ON :${config.label}(${config.property})
                WITH CONFIG {dimension: ${config.dimensions}, metric: "$metric", capacity: $capacity}
                """.trimIndent()
            )
        )
    }

    private fun Map<String, Any?>.toVectorIndexInfo(): VectorIndexInfo {
        val dimensions = (this["dimension"] as? Number)?.toInt()
            ?: error("Memgraph vector index missing 'dimension' in show_index_info output: $this")
        val metric = (this["metric"] as? String)
            ?: error("Memgraph vector index missing 'metric' in show_index_info output: $this")
        return VectorIndexInfo(
            name = this["index_name"] as String?,
            label = this["label"].toString(),
            property = this["property"].toString(),
            dimensions = dimensions,
            similarityFunction = metric.fromMemgraphMetric(),
        )
    }

    private fun SimilarityFunction.toMemgraphMetric(): String = when (this) {
        SimilarityFunction.COSINE -> "cos"
        SimilarityFunction.EUCLIDEAN -> "l2sq"
    }

    private fun String.fromMemgraphMetric(): SimilarityFunction = when (this.lowercase()) {
        "cos" -> SimilarityFunction.COSINE
        "l2sq" -> SimilarityFunction.EUCLIDEAN
        else -> error("Unknown Memgraph vector metric: $this")
    }

    companion object {
        const val DEFAULT_CAPACITY = 10_000

        private const val SHOW_VECTOR_INDEXES =
            """CALL vector_search.show_index_info()
               YIELD index_name, label, property, dimension, metric
               RETURN {index_name: index_name, label: label, property: property,
                       dimension: dimension, metric: metric} AS row"""
    }
}