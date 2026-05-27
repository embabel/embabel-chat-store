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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Cypher-capture unit tests for [MemgraphVectorIndexManager].
 *
 * We don't run Memgraph in this project's test container set, so verify the *emitted*
 * Cypher / procedure calls instead. The shape of those statements is what differs from
 * Neo4j and is the substance of this implementation.
 */
class MemgraphVectorIndexManagerTest {

    private val persistenceManager = mock<PersistenceManager>()
    private val manager = MemgraphVectorIndexManager(persistenceManager)

    @Test
    fun `ensureIndex on empty database issues CREATE VECTOR INDEX with WITH CONFIG and cos metric`() {
        whenever(persistenceManager.query(any<QuerySpecification<Map<String, Any?>>>()))
            .thenReturn(emptyList<Map<String, Any?>>())
            .thenReturn(listOf(memgraphIndexRow(dimension = 1536, metric = "cos")))

        val result = manager.ensureIndex(VectorIndexConfig("StoredMessage", "embedding", 1536))

        val emitted = captureExecutedStatement()
        assertTrue(emitted.contains("CREATE VECTOR INDEX"))
        assertTrue(emitted.contains("ON :StoredMessage(embedding)"))
        assertTrue(emitted.contains("WITH CONFIG"))
        assertTrue(emitted.contains("dimension: 1536"))
        assertTrue(emitted.contains("metric: \"cos\""))
        assertInstanceOf(VectorIndexManager.EnsureResult.Created::class.java, result)
    }

    @Test
    fun `ensureIndex returns AlreadyMatching when introspection finds a matching index`() {
        whenever(persistenceManager.query(any<QuerySpecification<Map<String, Any?>>>()))
            .thenReturn(listOf(memgraphIndexRow(dimension = 1536, metric = "cos")))

        val result = manager.ensureIndex(VectorIndexConfig("StoredMessage", "embedding", 1536))

        assertInstanceOf(VectorIndexManager.EnsureResult.AlreadyMatching::class.java, result)
    }

    @Test
    fun `ensureIndex reports drift when stored dimensions differ`() {
        whenever(persistenceManager.query(any<QuerySpecification<Map<String, Any?>>>()))
            .thenReturn(listOf(memgraphIndexRow(dimension = 1536, metric = "cos")))

        val result = manager.ensureIndex(VectorIndexConfig("StoredMessage", "embedding", 3072))

        val drift = assertInstanceOf(VectorIndexManager.EnsureResult.Drift::class.java, result)
        assertEquals(1536, drift.existing.dimensions)
        assertEquals(3072, drift.requested.dimensions)
    }

    @Test
    fun `EUCLIDEAN maps to l2sq metric in the emitted CREATE`() {
        whenever(persistenceManager.query(any<QuerySpecification<Map<String, Any?>>>()))
            .thenReturn(emptyList<Map<String, Any?>>())
            .thenReturn(listOf(memgraphIndexRow(dimension = 128, metric = "l2sq")))

        manager.ensureIndex(
            VectorIndexConfig(
                "StoredMessage", "embedding", 128,
                similarityFunction = SimilarityFunction.EUCLIDEAN,
            )
        )

        val emitted = captureExecutedStatement()
        assertTrue(emitted.contains("metric: \"l2sq\""))
    }

    @Test
    fun `recreateIndex issues DROP then CREATE`() {
        whenever(persistenceManager.query(any<QuerySpecification<Map<String, Any?>>>()))
            .thenReturn(listOf(memgraphIndexRow(dimension = 1536, metric = "cos")))

        manager.recreateIndex(VectorIndexConfig("StoredMessage", "embedding", 3072))

        val emitted = captureAllExecutedStatements()
        assertTrue(emitted.any { it.startsWith("DROP VECTOR INDEX") })
        assertTrue(emitted.any { it.contains("CREATE VECTOR INDEX") && it.contains("dimension: 3072") })
    }

    @Test
    fun `findIndex returns null when introspection throws`() {
        whenever(persistenceManager.query(any<QuerySpecification<Map<String, Any?>>>()))
            .thenThrow(RuntimeException("vector_search procedure unavailable"))

        assertNull(manager.findIndex("StoredMessage", "embedding"))
    }

    private fun memgraphIndexRow(
        indexName: String = "StoredMessage_embedding_vector",
        label: String = "StoredMessage",
        property: String = "embedding",
        dimension: Int,
        metric: String,
    ): Map<String, Any?> = mapOf(
        "index_name" to indexName,
        "label" to label,
        "property" to property,
        "dimension" to dimension,
        "metric" to metric,
    )

    private fun captureExecutedStatement(): String {
        val captor = argumentCaptor<QuerySpecification<*>>()
        verify(persistenceManager).execute(captor.capture())
        return captor.firstValue.statement?.text ?: ""
    }

    private fun captureAllExecutedStatements(): List<String> {
        val captor = argumentCaptor<QuerySpecification<*>>()
        verify(persistenceManager, org.mockito.kotlin.atLeastOnce()).execute(captor.capture())
        return captor.allValues.map { it.statement?.text ?: "" }
    }
}