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

import com.embabel.chat.store.model.MessageData
import com.embabel.chat.store.model.MessageDataQueryDsl
import com.embabel.chat.store.model.loadAll
import com.embabel.chat.store.model.patch
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.anyOf
import org.drivine.query.dsl.not
import org.drivine.query.dsl.notIn
import org.drivine.query.dsl.query
import org.slf4j.LoggerFactory

/**
 * One pass rewriting every message vector not already made by [modelName].
 *
 * Paged rather than loaded whole: a busy world's message history is the largest thing in the store,
 * and one `embed` call per page bounds both the request size and the memory held. Each message's
 * vector commits as it is written, so an interrupted run resumes where it stopped.
 *
 * The scan and the writes go through the object manager and not a statement written by hand, so
 * the run is the same on a store that does not speak Cypher. A page is therefore loaded as whole
 * messages, the vectors to be replaced among them.
 *
 * A failed `embed` call — including one that answers with the wrong number of vectors — is split
 * in half and retried, down to single messages, so a message the service refuses costs only
 * itself. Messages refused on their own are excluded from later pages (else the scan would hand
 * them back forever) and reported. After [abandonAfter] consecutive failed calls the service is
 * taken to be down and the run stops.
 */
internal class MessageReembedRun(
    private val graphObjectManager: StatelessGraphObjectManager,
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

    private fun embedIsolating(batch: List<MessageData>) {
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
    private fun tryEmbed(batch: List<MessageData>): List<List<Double>>? = try {
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

    private fun write(batch: List<MessageData>, vectors: List<List<Double>>) {
        batch.forEachIndexed { i, message ->
            val vector = vectors[i]
            if (write(message, FloatArray(vector.size) { vector[it].toFloat() })) rewritten++
        }
    }

    /**
     * Write one message's vector, and of the message nothing else, in one statement. A message
     * changed since the page was loaded, by a narration for one, is written all the same: the
     * vector and its model are fields no other writer touches.
     *
     * @return false when the message was deleted after the page was loaded
     */
    private fun write(message: MessageData, vector: FloatArray): Boolean =
        graphObjectManager.patch<MessageData>(message.messageId) {
            fields {
                query.embedding setTo vector
                query.embeddingModel setTo modelName
            }
        }.found

    private fun loadPendingPage(): List<MessageData> = graphObjectManager.loadAll<MessageData> {
        pending()
        limit(pageSize)
    }

    private fun countPending(): Int =
        graphObjectManager.count(MessageData::class.java, MessageDataQueryDsl.INSTANCE) { pending() }.toInt()

    /**
     * Still to rewrite: has content, not made by the current model — a null `embeddingModel` (a
     * message whose embed failed when it was saved) counts — and not already refused in this run.
     *
     * Empty content is ruled out as `not { eq "" }` and not as `neq ""`: Oracle compares a text
     * over 4,000 characters as absent, which `neq` would take for a message with no content.
     */
    private fun GraphQuerySpec<MessageDataQueryDsl>.pending() {
        val failed = failedIds.toList()
        where {
            query.content.isNotNull()
            not { query.content eq "" }
            anyOf {
                query.embeddingModel.isNull()
                query.embeddingModel neq modelName
            }
            if (failed.isNotEmpty()) query.messageId notIn failed
        }
    }

    private companion object {
        const val ABANDON_SLACK = 3

        fun ceilLog2(n: Int): Int = if (n <= 1) 0 else Int.SIZE_BITS - Integer.numberOfLeadingZeros(n - 1)
    }
}
