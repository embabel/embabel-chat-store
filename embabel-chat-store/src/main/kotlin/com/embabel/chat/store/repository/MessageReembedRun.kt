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

import com.embabel.chat.store.repository.ChatSessionRepositoryImpl.MessageText
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.slf4j.LoggerFactory

/**
 * One pass rewriting every message vector not already made by [modelName].
 *
 * Paged rather than loaded whole: a busy world's message history is the largest thing in the store,
 * and one `embed` call per page bounds both the request size and the memory held. Each page's
 * writes commit as they land, so an interrupted run resumes where it stopped.
 *
 * A failed `embed` call — including one that answers with the wrong number of vectors — is split
 * in half and retried, down to single messages, so a message the service refuses costs only
 * itself. Messages refused on their own are excluded from later pages (else the scan would hand
 * them back forever) and reported. After [abandonAfter] consecutive failed calls the service is
 * taken to be down and the run stops.
 */
internal class MessageReembedRun(
    private val persistenceManager: PersistenceManager,
    private val modelName: String,
    private val embed: (List<String>) -> List<List<Double>>,
    private val pageSize: Int,
) {
    private val logger = LoggerFactory.getLogger(MessageReembedRun::class.java)

    /**
     * Enough consecutive failures to walk one page down to a single refused message and its
     * sibling, plus slack; more than that and it is the service, not the messages.
     */
    private val abandonAfter = ceilLog2(pageSize) + ABANDON_SLACK

    private val failedIds = LinkedHashSet<String>()
    private var rewritten = 0
    private var consecutiveFailures = 0
    private var lastError: Throwable? = null
    private var abandoned = false

    /**
     * @return how many messages were rewritten
     * @throws MessageReembedIncompleteException when any message was left pending; its report's
     * `indexRecreated` is false, as index handling belongs to the caller
     */
    fun execute(): Int {
        while (!abandoned) {
            val page = loadPendingPage()
            if (page.isEmpty()) break
            embedIsolating(page)
        }
        if (failedIds.isEmpty() && !abandoned) return rewritten
        val notAttempted = if (abandoned) countPending() else 0
        throw MessageReembedIncompleteException(
            failedMessageIds = failedIds.toList(),
            notAttempted = notAttempted,
            report = MessageReembedReport(messages = rewritten, indexRecreated = false),
            abandoned = abandoned,
            cause = lastError,
        )
    }

    private fun embedIsolating(batch: List<MessageText>) {
        if (abandoned) return
        val vectors = tryEmbed(batch)
        when {
            vectors != null -> write(batch, vectors)
            batch.size == 1 -> {
                val id = batch.single().messageId
                failedIds += id
                logger.warn("Re-embed of message {} with model {} failed: {}", id, modelName, lastError?.message, lastError)
            }
            else -> {
                val mid = (batch.size + 1) / 2
                embedIsolating(batch.subList(0, mid))
                embedIsolating(batch.subList(mid, batch.size))
            }
        }
    }

    /** The batch's vectors, or null after recording a failed call. */
    private fun tryEmbed(batch: List<MessageText>): List<List<Double>>? = try {
        embed(batch.map { it.content }).also { vectors ->
            check(vectors.size == batch.size) {
                "Embedding service returned ${vectors.size} vectors for ${batch.size} texts"
            }
            consecutiveFailures = 0
        }
    } catch (e: Exception) {
        lastError = e
        consecutiveFailures++
        logger.debug("Re-embed call for {} messages failed ({} in a row): {}", batch.size, consecutiveFailures, e.message)
        if (consecutiveFailures >= abandonAfter) {
            abandoned = true
            logger.warn(
                "Abandoning message re-embed with model {} after {} consecutive failed embed calls: {}",
                modelName, consecutiveFailures, e.message,
            )
        }
        null
    }

    private fun write(batch: List<MessageText>, vectors: List<List<Double>>) {
        persistenceManager.executeBatch(
            batch.mapIndexed { i, row ->
                QuerySpecification
                    .withStatement(
                        "MATCH (m:StoredMessage {messageId: ${'$'}id}) " +
                            "SET m.embedding = ${'$'}embedding, m.embeddingModel = ${'$'}model",
                    )
                    .bind(mapOf("id" to row.messageId, "embedding" to vectors[i], "model" to modelName))
            },
        )
        rewritten += batch.size
    }

    private fun loadPendingPage(): List<MessageText> = persistenceManager.query(
        QuerySpecification
            .withStatement(
                """
                MATCH (m:StoredMessage)
                WHERE $PENDING
                RETURN { messageId: m.messageId, content: m.content }
                LIMIT ${'$'}limit
                """.trimIndent(),
            )
            .bind(mapOf("model" to modelName, "failed" to failedIds.toList(), "limit" to pageSize))
            .transform(MessageText::class.java),
    )

    private fun countPending(): Int = persistenceManager.getOne(
        QuerySpecification
            .withStatement("MATCH (m:StoredMessage) WHERE $PENDING RETURN count(m)")
            .bind(mapOf("model" to modelName, "failed" to failedIds.toList()))
            .transform(Long::class.java),
    ).toInt()

    private companion object {
        const val ABANDON_SLACK = 3

        /**
         * Still to rewrite: has content, not made by the current model — a null `embeddingModel`
         * (a message whose embed failed when it was saved) counts — and not already refused in
         * this run.
         */
        const val PENDING = "m.content IS NOT NULL AND m.content <> '' " +
            "AND coalesce(m.embeddingModel, '') <> \$model AND NOT m.messageId IN \$failed"

        fun ceilLog2(n: Int): Int = if (n <= 1) 0 else Int.SIZE_BITS - Integer.numberOfLeadingZeros(n - 1)
    }
}
