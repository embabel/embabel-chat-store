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
 * Cypher-capture unit tests for [FalkorDbVectorIndexManager].
 *
 * FalkorDB isn't in this project's test container set; we verify the emitted Cypher
 * has the right shape (bare-identifier options, no `IF NOT EXISTS`, no user-supplied
 * index name) and that introspection correctly identifies vector indexes from
 * `db.indexes()`.
 */
class FalkorDbVectorIndexManagerTest {

    private val persistenceManager = mock<PersistenceManager>()
    private val manager = FalkorDbVectorIndexManager(persistenceManager)

    @Test
    fun `ensureIndex on empty database emits CREATE VECTOR INDEX with bare-identifier options`() {
        whenever(persistenceManager.query(any<QuerySpecification<Map<String, Any?>>>()))
            .thenReturn(emptyList<Map<String, Any?>>())
            .thenReturn(listOf(falkorIndexRow(dimension = 1536, similarity = "cosine")))

        val result = manager.ensureIndex(VectorIndexConfig("StoredMessage", "embedding", 1536))

        val emitted = captureExecutedStatement()
        assertTrue(emitted.contains("CREATE VECTOR INDEX"))
        assertTrue(emitted.contains("FOR (n:StoredMessage) ON (n.embedding)"))
        assertTrue(emitted.contains("dimension: 1536"))
        assertTrue(emitted.contains("similarityFunction: 'cosine'"))
        assertInstanceOf(VectorIndexManager.EnsureResult.Created::class.java, result)
    }

    @Test
    fun `findIndex reports no index name (FalkorDB doesn't carry one)`() {
        whenever(persistenceManager.query(any<QuerySpecification<Map<String, Any?>>>()))
            .thenReturn(listOf(falkorIndexRow(dimension = 1536, similarity = "cosine")))

        val info = manager.findIndex("StoredMessage", "embedding")

        assertNull(info?.name)
        assertEquals(1536, info?.dimensions)
        assertEquals(SimilarityFunction.COSINE, info?.similarityFunction)
    }

    @Test
    fun `findIndex ignores rows with non-VECTOR index types on the same property`() {
        val falkorMixedRow = mapOf(
            "label" to "StoredMessage",
            "properties" to listOf("embedding"),
            "types" to mapOf("embedding" to listOf("EXACT")),
            "options" to mapOf<String, Any?>(),
        )
        whenever(persistenceManager.query(any<QuerySpecification<Map<String, Any?>>>()))
            .thenReturn(listOf(falkorMixedRow))

        assertNull(manager.findIndex("StoredMessage", "embedding"))
    }

    @Test
    fun `recreateIndex issues DROP scoped to label and property, then CREATE`() {
        whenever(persistenceManager.query(any<QuerySpecification<Map<String, Any?>>>()))
            .thenReturn(listOf(falkorIndexRow(dimension = 1536, similarity = "cosine")))

        manager.recreateIndex(VectorIndexConfig("StoredMessage", "embedding", 3072))

        val emitted = captureAllExecutedStatements()
        assertTrue(emitted.any { it.contains("DROP VECTOR INDEX FOR (n:StoredMessage) ON (n.embedding)") })
        assertTrue(emitted.any { it.contains("CREATE VECTOR INDEX") && it.contains("dimension: 3072") })
    }

    @Test
    fun `EUCLIDEAN similarity maps to lowercase 'euclidean' in the emitted CREATE`() {
        whenever(persistenceManager.query(any<QuerySpecification<Map<String, Any?>>>()))
            .thenReturn(emptyList<Map<String, Any?>>())
            .thenReturn(listOf(falkorIndexRow(dimension = 128, similarity = "euclidean")))

        manager.ensureIndex(
            VectorIndexConfig(
                "StoredMessage", "embedding", 128,
                similarityFunction = SimilarityFunction.EUCLIDEAN,
            )
        )

        val emitted = captureExecutedStatement()
        assertTrue(emitted.contains("similarityFunction: 'euclidean'"))
    }

    private fun falkorIndexRow(
        label: String = "StoredMessage",
        property: String = "embedding",
        dimension: Int,
        similarity: String,
    ): Map<String, Any?> = mapOf(
        "label" to label,
        "properties" to listOf(property),
        "types" to mapOf(property to listOf("VECTOR")),
        "options" to mapOf(
            "dimension" to dimension,
            "similarityFunction" to similarity,
        ),
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