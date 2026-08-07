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
package com.embabel.chat.store.repository

import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Backfills `lastActivityAt` for sessions created before activity ordering existed.
 *
 * Such sessions still read correctly without this — `SessionData.lastActivityAt` carries
 * Drivine's `@Default` and hydrates as `createdAt` — but a value that exists only client-side
 * cannot satisfy a keyset comparison, so under [SessionOrder.LAST_ACTIVITY] the session would
 * be dropped from every page after the first. This materialises it in the graph, running
 * until none are left and then recording a marker node, so the common case on subsequent
 * boots is a single lookup rather than a full label scan.
 *
 * Work is done in bounded batches rather than one transaction: the un-backfilled set cannot
 * use the `lastActivityAt` range index (Neo4j does not index nulls), so the first run on a
 * large store would otherwise be a single long write transaction on startup.
 */
class SessionActivityMigration(
    private val persistenceManager: PersistenceManager,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    private val logger = LoggerFactory.getLogger(SessionActivityMigration::class.java)

    fun migrate() {
        if (isComplete()) {
            logger.debug("Session activity backfill already complete")
            return
        }
        var total = 0L
        do {
            val updated = backfillBatch()
            total += updated
            if (updated > 0) {
                logger.info("Backfilled lastActivityAt for {} sessions ({} total)", updated, total)
            }
        } while (updated > 0)
        markComplete()
        if (total > 0) {
            logger.info("Session activity backfill complete: {} sessions updated", total)
        }
    }

    /**
     * Backfill at most [batchSize] sessions, returning how many were updated.
     *
     * The coalesce falls back to [Instant.EPOCH] so that a legacy session with neither
     * messages nor a `createdAt` still gets a value. Without that floor the SET would be
     * null, which in Cypher *removes* the property, leaving the session matching the same
     * filter forever and preventing the migration from ever converging.
     */
    private fun backfillBatch(): Long = persistenceManager.getOne(
        QuerySpecification
            .withStatement(
                """
                MATCH (session:ChatSession)
                WHERE session.lastActivityAt IS NULL
                WITH session LIMIT ${'$'}batchSize
                OPTIONAL MATCH (session)-[:HAS_MESSAGE]->(message:StoredMessage)
                WITH session, max(message.createdAt) AS latestMessageAt
                SET session.lastActivityAt = coalesce(latestMessageAt, session.createdAt, ${'$'}floor)
                RETURN count(session) AS updated
                """.trimIndent()
            )
            .bind(mapOf("batchSize" to batchSize, "floor" to Instant.EPOCH))
            .map { (it as Number).toLong() }
    )

    private fun isComplete(): Boolean = persistenceManager.getOne(
        QuerySpecification
            .withStatement(
                "MATCH (m:`$MARKER_LABEL` {name: \$name}) RETURN count(m) AS found"
            )
            .bind(mapOf("name" to MARKER_NAME))
            .map { (it as Number).toLong() }
    ) > 0

    private fun markComplete() {
        persistenceManager.execute(
            QuerySpecification
                .withStatement(
                    "MERGE (m:`$MARKER_LABEL` {name: \$name}) SET m.completedAt = timestamp()"
                )
                .bind(mapOf("name" to MARKER_NAME))
        )
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 1000
        private const val MARKER_LABEL = "_ChatStoreMigration"
        private const val MARKER_NAME = "session-activity"
    }
}
