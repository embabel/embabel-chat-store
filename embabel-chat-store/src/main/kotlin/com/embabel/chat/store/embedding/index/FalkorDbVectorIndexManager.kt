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
 * [VectorIndexManager] for FalkorDB 4.x.
 *
 * FalkorDB identifies indexes by (label, property); there is no user-supplied index
 * name. [VectorIndexConfig.name] is therefore ignored on FalkorDB, and
 * [VectorIndexInfo.name] is always null. Creation uses `OPTIONS {…}` like Neo4j but
 * with bare-identifier keys (`dimension`, `similarityFunction`) and string similarity
 * values that match the canonical names (`'cosine'`, `'euclidean'`).
 *
 * FalkorDB lacks `IF NOT EXISTS`, so existence is checked via `CALL db.indexes()`
 * before issuing the create. Drop similarly requires guarding.
 *
 * Note: the exact shape of the `options` map returned by `db.indexes()` for vector
 * indexes is not fully documented; this implementation reads `dimension` and
 * `similarityFunction` keys, which match what FalkorDB accepts on create. Verify
 * against a live instance the first time drift detection is exercised.
 */
class FalkorDbVectorIndexManager(
    private val persistenceManager: PersistenceManager,
) : VectorIndexManager {

    private val logger = LoggerFactory.getLogger(FalkorDbVectorIndexManager::class.java)

    override fun ensureIndex(config: VectorIndexConfig): VectorIndexManager.EnsureResult {
        val existing = findIndex(config.label, config.property)
        if (existing == null) {
            createIndex(config)
            val created = findIndex(config.label, config.property)
                ?: error("FalkorDB reported success creating vector index but introspection cannot find it: ${config.label}(${config.property})")
            logger.info("Created FalkorDB vector index on {}({}) — {} dims, {}",
                created.label, created.property, created.dimensions, created.similarityFunction)
            return VectorIndexManager.EnsureResult.Created(created)
        }
        return if (existing.matches(config)) {
            logger.debug("FalkorDB vector index already matches requested config: {}({})",
                existing.label, existing.property)
            VectorIndexManager.EnsureResult.AlreadyMatching(existing)
        } else {
            logger.warn(
                "FalkorDB vector index on {}({}) does not match requested config: " +
                    "existing dims={}/similarity={}, requested dims={}/similarity={}. " +
                    "Call recreateIndex() and re-embed existing messages to resolve.",
                existing.label, existing.property,
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
            "Recreated FalkorDB vector index on {}({}) with {} dims, {} — " +
                "previously-stored vectors are now stale and must be re-embedded.",
            config.label, config.property, config.dimensions, config.similarityFunction,
        )
    }

    override fun findIndex(label: String, property: String): VectorIndexInfo? {
        val rows = try {
            persistenceManager.query(
                QuerySpecification
                    .withStatement(SHOW_INDEXES)
                    .transform(Map::class.java)
            )
        } catch (e: Exception) {
            logger.debug("Could not query FalkorDB indexes (probably none exist): {}", e.message)
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return rows.asSequence()
            .map { it as Map<String, Any?> }
            .firstOrNull { row -> row.matchesVectorIndexOn(label, property) }
            ?.toVectorIndexInfo()
    }

    override fun dropIndex(label: String, property: String) {
        if (findIndex(label, property) == null) return
        persistenceManager.execute(
            QuerySpecification.withStatement("DROP VECTOR INDEX FOR (n:$label) ON (n.$property)")
        )
        logger.info("Dropped FalkorDB vector index on {}({})", label, property)
    }

    private fun createIndex(config: VectorIndexConfig) {
        val similarity = config.similarityFunction.name.lowercase()
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                CREATE VECTOR INDEX FOR (n:${config.label}) ON (n.${config.property})
                OPTIONS {dimension: ${config.dimensions}, similarityFunction: '$similarity'}
                """.trimIndent()
            )
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.matchesVectorIndexOn(label: String, property: String): Boolean {
        if (this["label"]?.toString() != label) return false
        val properties = this["properties"] as? List<String> ?: return false
        if (property !in properties) return false
        val types = this["types"]
        return when (types) {
            is Map<*, *> -> (types[property] as? List<*>)?.any { it?.toString()?.uppercase() == "VECTOR" } == true
            is List<*> -> types.any { it?.toString()?.uppercase() == "VECTOR" }
            else -> false
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.toVectorIndexInfo(): VectorIndexInfo {
        val options = this["options"] as? Map<String, Any?>
            ?: error("FalkorDB vector index row has no 'options' map: $this")
        val dimensions = (options["dimension"] as? Number)?.toInt()
            ?: error("FalkorDB vector index options missing 'dimension': $options")
        val similarity = (options["similarityFunction"] as? String)
            ?: error("FalkorDB vector index options missing 'similarityFunction': $options")
        val properties = this["properties"] as List<String>
        return VectorIndexInfo(
            name = null,
            label = this["label"].toString(),
            property = properties.first(),
            dimensions = dimensions,
            similarityFunction = SimilarityFunction.valueOf(similarity.uppercase()),
        )
    }

    companion object {
        private const val SHOW_INDEXES =
            """CALL db.indexes()
               YIELD label, properties, types, options
               RETURN {label: label, properties: properties, types: types, options: options} AS row"""
    }
}