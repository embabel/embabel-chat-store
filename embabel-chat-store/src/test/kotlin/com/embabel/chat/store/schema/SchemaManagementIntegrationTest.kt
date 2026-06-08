package com.embabel.chat.store.schema

import com.embabel.chat.store.TestApplication
import org.drivine.connection.DatabaseRegistry
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.query.QuerySpecification
import org.drivine.schema.EnsureResult
import org.drivine.schema.SchemaCatalog
import org.drivine.schema.SchemaManager
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.UUID

/**
 * Verifies that the Drivine schema layer this module migrated to actually creates and
 * enforces schema against a real graph database — the coverage that disappeared when the
 * bespoke per-engine vector-index managers were deleted, and the canary for future
 * Drivine version bumps.
 *
 * Deliberately **not** `@Transactional`: schema DDL runs in auto-commit (it cannot run
 * inside a data transaction), so these tests manage their own cleanup. Throwaway labels
 * keep them isolated from the real `ChatSession`/`StoredMessage`/`User` schema other tests
 * rely on. Faithfulness of the *declared* specs (real labels/properties) is covered
 * separately by the autoconfigure wiring test.
 */
@SpringBootTest(classes = [TestApplication::class])
class SchemaManagementIntegrationTest {

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    @Autowired
    private lateinit var persistenceManagerFactory: PersistenceManagerFactory

    @Autowired
    private lateinit var databaseRegistry: DatabaseRegistry

    private val vectorLabel = "SchemaTestVector"
    private val vectorProperty = "embedding"
    private val uniqueLabel = "SchemaTestUnique"
    private val uniqueProperty = "uid"

    @AfterEach
    fun cleanup() {
        runCatching { persistenceManager.indexes.drop(VectorIndexSpec(vectorLabel, vectorProperty, 8)) }
        runCatching { persistenceManager.constraints.drop(UniquenessConstraintSpec(uniqueLabel, uniqueProperty)) }
        runCatching { execute("MATCH (n:$vectorLabel) DETACH DELETE n") }
        runCatching { execute("MATCH (n:$uniqueLabel) DETACH DELETE n") }
        runCatching { execute("MATCH (n:_DrivineSchema) DETACH DELETE n") }
    }

    // ==================== Tier 1: creation + enforcement ====================

    @Test
    fun `ensure creates a vector index with the requested shape`() {
        val spec = VectorIndexSpec(vectorLabel, vectorProperty, dimensions = 8, similarity = SimilarityFunction.COSINE)

        val result = persistenceManager.indexes.ensure(spec)

        // First run creates it; a warm shared container may already have it from a prior run.
        assert(result is EnsureResult.Created || result is EnsureResult.AlreadyMatching) {
            "Expected Created or AlreadyMatching, got $result"
        }
        val info = persistenceManager.indexes.find(spec)
        assertNotNull(info, "vector index should exist after ensure")
        assertEquals(8, info!!.dimensions)
        assertEquals(SimilarityFunction.COSINE, info.similarity)
    }

    @Test
    fun `ensure creates a uniqueness constraint that rejects duplicates`() {
        val spec = UniquenessConstraintSpec(uniqueLabel, uniqueProperty)

        val result = persistenceManager.constraints.ensure(spec)
        assert(result is EnsureResult.Created || result is EnsureResult.AlreadyMatching) {
            "Expected Created or AlreadyMatching, got $result"
        }
        assertNotNull(persistenceManager.constraints.find(spec), "constraint should exist after ensure")

        val id = UUID.randomUUID().toString()
        createUniqueNode(id) // first insert succeeds

        // Second insert with the same id violates the constraint (auto-commit → surfaces now).
        assertThrows(Exception::class.java) { createUniqueNode(id) }
    }

    // ==================== Tier 2: drift + version behaviour ====================

    @Test
    fun `ensuring the same index twice is idempotent`() {
        val spec = VectorIndexSpec(vectorLabel, vectorProperty, dimensions = 8)

        persistenceManager.indexes.ensure(spec)
        val second = persistenceManager.indexes.ensure(spec)

        assertInstanceOf(EnsureResult.AlreadyMatching::class.java, second)
    }

    @Test
    fun `a dimension change is reported as drift and not silently applied`() {
        persistenceManager.indexes.ensure(VectorIndexSpec(vectorLabel, vectorProperty, dimensions = 8))

        // Same identity (label/property), different shape (dimensions) → drift, nothing dropped.
        val drift = persistenceManager.indexes.ensure(VectorIndexSpec(vectorLabel, vectorProperty, dimensions = 16))
        assertInstanceOf(EnsureResult.Drift::class.java, drift)
        assertEquals(
            8,
            persistenceManager.indexes.find(VectorIndexSpec(vectorLabel, vectorProperty, 16))!!.dimensions,
            "drift must not change the existing index"
        )

        // Explicit, destructive recreate is the only way to adopt the new shape.
        persistenceManager.indexes.recreate(VectorIndexSpec(vectorLabel, vectorProperty, dimensions = 16))
        assertEquals(
            16,
            persistenceManager.indexes.find(VectorIndexSpec(vectorLabel, vectorProperty, 16))!!.dimensions
        )
    }

    @Test
    fun `schema manager enforces a versioned catalog and survives a version change`() {
        // Deep recreate-on-version-change semantics are Drivine's own; here we verify our
        // wiring: a versioned catalog enforces cleanly and the index stays valid when the
        // version token (e.g. the embedding model id) changes.
        val specOf = { VectorIndexSpec(vectorLabel, vectorProperty, dimensions = 8) }

        schemaManager(SchemaCatalog.of(specOf()).withVersion("model-A")).enforce()
        assertNotNull(persistenceManager.indexes.find(specOf()), "index should exist after first enforce")

        schemaManager(SchemaCatalog.of(specOf()).withVersion("model-A")).enforce() // same token
        assertEquals(8, persistenceManager.indexes.find(specOf())!!.dimensions)

        schemaManager(SchemaCatalog.of(specOf()).withVersion("model-B")).enforce() // changed token
        assertEquals(
            8,
            persistenceManager.indexes.find(specOf())!!.dimensions,
            "index should remain valid after a version change"
        )
    }

    private fun schemaManager(vararg catalogs: SchemaCatalog): SchemaManager =
        SchemaManager(persistenceManagerFactory, databaseRegistry, catalogs.toList())

    private fun createUniqueNode(id: String) =
        persistenceManager.execute(
            QuerySpecification
                .withStatement("CREATE (n:$uniqueLabel {$uniqueProperty: \$id})")
                .bind(mapOf("id" to id))
        )

    private fun execute(cypher: String) =
        persistenceManager.execute(QuerySpecification.withStatement(cypher))
}