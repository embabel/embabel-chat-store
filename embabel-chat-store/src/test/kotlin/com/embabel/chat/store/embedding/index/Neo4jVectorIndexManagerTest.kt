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

import com.embabel.chat.store.TestApplication
import org.drivine.manager.PersistenceManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

/**
 * Integration test for [Neo4jVectorIndexManager] against the Drivine Neo4j testcontainer.
 *
 * Drivine's testcontainer runs Neo4j 5.x; vector index DDL is supported. Each test
 * starts by dropping any leftover index to keep runs independent.
 */
@SpringBootTest(classes = [TestApplication::class])
class Neo4jVectorIndexManagerTest {

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    private lateinit var manager: Neo4jVectorIndexManager

    private val testLabel = "VectorIndexTestNode"
    private val testProperty = "embedding"

    @BeforeEach
    fun setUp() {
        manager = Neo4jVectorIndexManager(persistenceManager)
        manager.dropIndex(testLabel, testProperty)
    }

    @AfterEach
    fun tearDown() {
        manager.dropIndex(testLabel, testProperty)
    }

    @Test
    @Transactional(readOnly = true)
    fun `ensureIndex on empty database creates the index`() {
        val config = VectorIndexConfig(
            label = testLabel,
            property = testProperty,
            dimensions = 1536,
        )
        val result = manager.ensureIndex(config)

        assertInstanceOf(VectorIndexManager.EnsureResult.Created::class.java, result)
        val info = (result as VectorIndexManager.EnsureResult.Created).info
        assertEquals(testLabel, info.label)
        assertEquals(testProperty, info.property)
        assertEquals(1536, info.dimensions)
        assertEquals(SimilarityFunction.COSINE, info.similarityFunction)
        assertNotNull(info.name)
    }

    @Test
    @Transactional(readOnly = true)
    fun `second ensureIndex is a no-op when config matches`() {
        val config = VectorIndexConfig(testLabel, testProperty, dimensions = 1536)
        manager.ensureIndex(config)

        val second = manager.ensureIndex(config)

        assertInstanceOf(VectorIndexManager.EnsureResult.AlreadyMatching::class.java, second)
    }

    @Test
    @Transactional(readOnly = true)
    fun `ensureIndex reports drift when dimensions differ`() {
        manager.ensureIndex(VectorIndexConfig(testLabel, testProperty, dimensions = 1536))

        val drift = manager.ensureIndex(VectorIndexConfig(testLabel, testProperty, dimensions = 3072))

        assertInstanceOf(VectorIndexManager.EnsureResult.Drift::class.java, drift)
        val driftResult = drift as VectorIndexManager.EnsureResult.Drift
        assertEquals(1536, driftResult.existing.dimensions)
        assertEquals(3072, driftResult.requested.dimensions)
    }

    @Test
    @Transactional(readOnly = true)
    fun `recreateIndex drops and recreates with the new shape`() {
        manager.ensureIndex(VectorIndexConfig(testLabel, testProperty, dimensions = 1536))

        manager.recreateIndex(VectorIndexConfig(testLabel, testProperty, dimensions = 3072))

        val info = manager.findIndex(testLabel, testProperty)
        assertNotNull(info)
        assertEquals(3072, info!!.dimensions)
    }

    @Test
    @Transactional(readOnly = true)
    fun `dropIndex is a no-op when the index does not exist`() {
        manager.dropIndex(testLabel, testProperty)
        assertNull(manager.findIndex(testLabel, testProperty))
    }

    @Test
    @Transactional(readOnly = true)
    fun `findIndex returns null when no index exists`() {
        assertNull(manager.findIndex(testLabel, testProperty))
    }

    @Test
    @Transactional(readOnly = true)
    fun `respects explicit index name in the config`() {
        val explicitName = "my_custom_vector_index"
        manager.ensureIndex(
            VectorIndexConfig(testLabel, testProperty, dimensions = 256, name = explicitName)
        )

        val info = manager.findIndex(testLabel, testProperty)
        assertEquals(explicitName, info?.name)
    }

    @Test
    @Transactional(readOnly = true)
    fun `euclidean similarity round-trips through introspection`() {
        manager.ensureIndex(
            VectorIndexConfig(
                testLabel, testProperty, dimensions = 128,
                similarityFunction = SimilarityFunction.EUCLIDEAN,
            )
        )

        val info = manager.findIndex(testLabel, testProperty)
        assertTrue(info != null && info.similarityFunction == SimilarityFunction.EUCLIDEAN)
    }
}