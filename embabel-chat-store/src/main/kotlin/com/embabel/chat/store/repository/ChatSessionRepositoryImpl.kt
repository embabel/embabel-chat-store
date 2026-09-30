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

import com.embabel.chat.store.event.SessionCreatedEvent
import com.embabel.chat.store.model.AttachmentData
import com.embabel.chat.store.model.AssetData
import com.embabel.chat.store.model.AttributedMessage
import com.embabel.chat.store.model.DeletableSession
import com.embabel.chat.store.model.MessageData
import com.embabel.chat.store.model.NewMessageInSession
import com.embabel.chat.store.model.SessionData
import com.embabel.chat.store.model.SessionParticipants
import com.embabel.chat.store.model.SessionRef
import com.embabel.chat.store.model.SessionSummary
import com.embabel.chat.store.model.SessionSummaryQueryDsl
import com.embabel.chat.store.model.SimpleStoredMessage
import com.embabel.chat.store.model.StoredSession
import com.embabel.chat.store.model.StoredSessionQueryDsl
import com.embabel.chat.MessageRole
import com.embabel.chat.store.model.StoredUser
import com.embabel.chat.store.model.UserRef
import com.embabel.chat.store.model.deleteAll
import com.embabel.chat.store.model.loadAll
import com.embabel.chat.store.model.owner
import org.drivine.manager.CascadeType
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.PersistenceManager
import org.drivine.manager.delete
import org.drivine.manager.load
import org.drivine.schema.EnsureResult
import org.drivine.schema.VectorIndexSpec
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.OrderBuilder
import org.drivine.query.dsl.OrderSpec
import org.drivine.query.dsl.query
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.Optional

/** Registers a typed order built outside the block to disambiguate Drivine's binary `asc`/`desc` overloads. */
context(builder: OrderBuilder<*>)
private fun registerOrder(order: OrderSpec) {
    builder(order)
}

/**
 * Drivine-based implementation of ChatSessionRepository.
 *
 * Uses GraphObjectManager with reified type extensions and generated query DSL
 * for type-safe graph operations.
 */
open class ChatSessionRepositoryImpl(
    private val graphObjectManager: GraphObjectManager,
    private val persistenceManager: PersistenceManager,
    private val eventPublisher: ApplicationEventPublisher? = null,
    private val clock: Clock = Clock.systemUTC(),
) : ChatSessionRepository {

    private val logger = LoggerFactory.getLogger(ChatSessionRepositoryImpl::class.java)

    /**
     * NOT `@Transactional`, deliberately.
     *
     * Neo4j will not take schema work and data work in one transaction: the DDL runs on its own
     * session and waits on the schema locks an open data transaction holds, while that transaction
     * waits to commit. Doing exactly this inside a transactional method deadlocked dice's
     * equivalent — the request never returned and the index was left dropped (embabel/dice#117).
     * So the DDL sits either side of the rewrite.
     *
     * Drop before rewriting rather than remake after: an index left standing through the run
     * spends it describing a width none of the rewritten vectors have. The remake is unconditional
     * once dropped — it runs whether the rewrite succeeded, stopped short, or threw — so no outcome
     * leaves message search without an index.
     *
     * @throws MessageReembedIncompleteException after the index is restored, when some messages
     * could not be embedded; everything else was written
     */
    override fun reembedMessages(
        modelName: String,
        spec: VectorIndexSpec,
        embed: (List<String>) -> List<List<Double>>,
    ): MessageReembedReport {
        // `ensure` is non-destructive and reports Drift against an index of a different shape, so
        // it asks "does the stored index still describe what this model produces?" as well as
        // creating one when absent. Wider or narrower is the same question.
        val shapeChanged = persistenceManager.indexes.ensure(spec) is EnsureResult.Drift
        if (shapeChanged) {
            logger.info("Message embedding shape changed; dropping {} and remaking it after the re-embed", spec.effectiveName)
            persistenceManager.indexes.drop(spec)
        }
        val outcome = runCatching { rewriteMessageEmbeddings(modelName, embed) }
        val failure = outcome.exceptionOrNull()?.let { e ->
            if (e is MessageReembedIncompleteException) e.withIndexRecreated(shapeChanged) else e
        }
        if (shapeChanged) restoreIndex(spec, failure)
        if (failure != null) {
            logger.warn("reembedMessages incomplete: model={} indexRecreated={}: {}", modelName, shapeChanged, failure.message)
            throw failure
        }
        val rewritten = outcome.getOrThrow()
        logger.info("reembedMessages done: messages={} model={} indexRecreated={}", rewritten, modelName, shapeChanged)
        return MessageReembedReport(messages = rewritten, indexRecreated = shapeChanged)
    }

    /**
     * Remake the message index. A failure here while the rewrite already failed is attached to
     * that failure rather than replacing it; alone, it is thrown.
     */
    private fun restoreIndex(spec: VectorIndexSpec, pending: Throwable?) {
        try {
            persistenceManager.indexes.ensure(spec)
        } catch (e: Exception) {
            logger.error("Could not remake message vector index {} after re-embed", spec.effectiveName, e)
            if (pending == null) throw e
            pending.addSuppressed(e)
        }
    }

    private fun MessageReembedIncompleteException.withIndexRecreated(indexRecreated: Boolean) =
        MessageReembedIncompleteException(
            failedMessageIds = failedMessageIds,
            notAttempted = notAttempted,
            report = report.copy(indexRecreated = indexRecreated),
            abandoned = abandoned,
            cause = cause,
        )

    /**
     * Rewrite every message vector not already made by [modelName], in pages; see
     * [MessageReembedRun] for how failed embed calls are isolated and when a run gives up.
     *
     * NOT `@Transactional`. It carried the annotation once, but [reembedMessages] calls it on
     * `this`, so the proxy never saw the call and it never applied. It is removed rather than made
     * to work: one transaction around the whole rewrite would roll back every page before a failure
     * and hold a busy history's writes open at once, and each page committing on its own is what
     * lets a rerun resume where a failed one stopped.
     *
     * @throws MessageReembedIncompleteException when any message was left pending
     */
    protected open fun rewriteMessageEmbeddings(
        modelName: String,
        embed: (List<String>) -> List<List<Double>>,
    ): Int = MessageReembedRun(persistenceManager, modelName, embed, REEMBED_PAGE_SIZE).execute()

    @Transactional
    override fun createSession(sessionId: String, owner: StoredUser, title: String?): StoredSession {
        logger.debug("Creating session {} for owner {}", sessionId, owner.id)

        val now = clock.instant()
        val session = StoredSession(
            session = SessionData(
                sessionId = sessionId,
                title = title,
                createdAt = now,
                lastActivityAt = now,
            ),
            owner = owner,
            messages = emptyList()
        )

        return graphObjectManager.save(session).also {
            eventPublisher?.publishEvent(SessionCreatedEvent(it))
        }
    }

    @Transactional
    override fun createSessionWithMessage(
        sessionId: String,
        owner: StoredUser,
        title: String?,
        messageData: MessageData,
        messageAuthor: StoredUser?,
        messageRecipient: StoredUser?
    ): StoredSession = createSessionWithMessageInternal(
        sessionId = sessionId,
        owner = owner,
        title = title,
        messageData = messageData,
        messageAuthor = messageAuthor,
        messageRecipient = messageRecipient,
        assets = emptyList(),
    )

    @Transactional
    override fun createSessionWithMessageAndAssets(
        sessionId: String,
        owner: StoredUser,
        title: String?,
        messageData: MessageData,
        messageAuthor: StoredUser?,
        messageRecipient: StoredUser?,
        assets: List<AssetData>,
    ): StoredSession = createSessionWithMessageInternal(
        sessionId = sessionId,
        owner = owner,
        title = title,
        messageData = messageData,
        messageAuthor = messageAuthor,
        messageRecipient = messageRecipient,
        assets = assets,
    )

    private fun createSessionWithMessageInternal(
        sessionId: String,
        owner: StoredUser,
        title: String?,
        messageData: MessageData,
        messageAuthor: StoredUser?,
        messageRecipient: StoredUser?,
        assets: List<AssetData>,
    ): StoredSession {
        logger.debug("Creating session {} with initial message for owner {}", sessionId, owner.id)

        val message = SimpleStoredMessage(
            message = messageData,
            author = messageAuthor,
            recipient = messageRecipient,
            assets = assets,
        )

        val now = clock.instant()
        val session = StoredSession(
            session = SessionData(
                sessionId = sessionId,
                title = title,
                createdAt = now,
                lastActivityAt = now,
            ),
            owner = owner,
            messages = listOf(message)
        )

        return graphObjectManager.save(session).also {
            eventPublisher?.publishEvent(SessionCreatedEvent(it))
        }
    }

    @Transactional(readOnly = true)
    override fun findBySessionId(sessionId: String): Optional<StoredSession> {
        return Optional.ofNullable(
            graphObjectManager.load<StoredSession>(sessionId)
        )
    }

    @Transactional(readOnly = true)
    override fun listSessionsForUser(userId: String, order: SessionOrder): List<StoredSession> {
        val orders = storedSessionOrders(order)
        return graphObjectManager.loadAll<StoredSession> {
            where {
                owner.id eq userId
            }
            orderBy {
                orders.forEach { registerOrder(it) }
            }
        }
    }

    @Transactional(readOnly = true)
    override fun listSessionsForUser(userId: String, page: SessionPageRequest): SessionPage<StoredSession> {
        val orders = storedSessionOrders(page.order)
        val cursor = page.cursor?.let { SessionCursorCodec.decode(it, page.order) }
        return loadSessionPage(page, loader = {
            graphObjectManager.loadAll<StoredSession> {
                where { owner.id eq userId }
                orderBy { orders.forEach { registerOrder(it) } }
                when (cursor) {
                    null -> Unit
                    is SessionCursor.Created -> seek {
                        query.session.sessionId after cursor.sessionId
                    }

                    is SessionCursor.LastActivity -> seek {
                        query.session.lastActivityAt after cursor.lastActivityAt
                        query.session.sessionId after cursor.sessionId
                    }
                }
                limit(page.pageSize + 1)
            }
        }, sessionData = { it.session })
    }

    @Transactional(readOnly = true)
    override fun listSessionSummariesForUser(userId: String, order: SessionOrder): List<SessionSummary> {
        val orders = sessionSummaryOrders(order)
        // SessionSummary folds the message count into the query (via @Count), so this
        // returns one flat row per session — no message bodies cross the wire.
        return graphObjectManager.loadAll<SessionSummary> {
            where {
                owner.id eq userId
            }
            orderBy {
                orders.forEach { registerOrder(it) }
            }
        }
    }

    @Transactional(readOnly = true)
    override fun listSessionSummariesForUser(
        userId: String,
        page: SessionPageRequest,
    ): SessionPage<SessionSummary> {
        val orders = sessionSummaryOrders(page.order)
        val cursor = page.cursor?.let { SessionCursorCodec.decode(it, page.order) }
        return loadSessionPage(page, loader = {
            graphObjectManager.loadAll<SessionSummary> {
                where { owner.id eq userId }
                orderBy { orders.forEach { registerOrder(it) } }
                when (cursor) {
                    null -> Unit
                    is SessionCursor.Created -> seek {
                        query.session.sessionId after cursor.sessionId
                    }

                    is SessionCursor.LastActivity -> seek {
                        query.session.lastActivityAt after cursor.lastActivityAt
                        query.session.sessionId after cursor.sessionId
                    }
                }
                limit(page.pageSize + 1)
            }
        }, sessionData = { it.session })
    }

    /**
     * Sort keys for [StoredSession] in the requested order. [SessionOrder.CREATED] needs no
     * tie-breaker: the session ID is unique, so it is already a total order.
     */
    private fun storedSessionOrders(order: SessionOrder): List<OrderSpec> {
        val session = StoredSessionQueryDsl.INSTANCE.session
        return when (order) {
            SessionOrder.CREATED -> listOf(session.sessionId.descending())
            SessionOrder.LAST_ACTIVITY -> listOf(session.lastActivityAt.descending(), session.sessionId.descending())
        }
    }

    /** Sort keys for [SessionSummary]; see [storedSessionOrders]. */
    private fun sessionSummaryOrders(order: SessionOrder): List<OrderSpec> {
        val session = SessionSummaryQueryDsl.INSTANCE.session
        return when (order) {
            SessionOrder.CREATED -> listOf(session.sessionId.descending())
            SessionOrder.LAST_ACTIVITY -> listOf(session.lastActivityAt.descending(), session.sessionId.descending())
        }
    }

    @Transactional(readOnly = true)
    override fun countSessionsForUser(userId: String): Long {
        return graphObjectManager.count(StoredSession::class.java, StoredSessionQueryDsl.INSTANCE) {
            where {
                owner.id eq userId
            }
        }
    }

    @Transactional
    override fun updateSessionTitle(sessionId: String, title: String) {
        val session = findBySessionId(sessionId).orElseThrow {
            IllegalArgumentException("Session not found: $sessionId")
        }
        val updated = session.copy(
            session = session.session.copy(title = title)
        )
        graphObjectManager.save(updated)
    }

    @Transactional
    override fun deleteSession(sessionId: String) {
        // Cascade through DeletableSession so the session and all of its messages
        // are hard-deleted. The view declares only HAS_MESSAGE, so the cascade
        // cannot reach the owner or message authors/recipients — DETACH DELETE of
        // each :StoredMessage drops those edges while leaving every :User intact.
        graphObjectManager.delete<DeletableSession>(sessionId, CascadeType.DELETE_ALL)
    }

    @Transactional
    override fun addMessage(
        sessionId: String,
        messageData: MessageData,
        author: StoredUser?,
        recipient: StoredUser?,
        attachments: List<AttachmentData>
    ): StoredSession = addMessageInternal(
        sessionId = sessionId,
        messageData = messageData,
        author = author,
        recipient = recipient,
        attachments = attachments,
        assets = emptyList(),
    )

    @Transactional
    override fun addMessageWithAssets(
        sessionId: String,
        messageData: MessageData,
        author: StoredUser?,
        recipient: StoredUser?,
        attachments: List<AttachmentData>,
        assets: List<AssetData>,
    ): StoredSession = addMessageInternal(
        sessionId = sessionId,
        messageData = messageData,
        author = author,
        recipient = recipient,
        attachments = attachments,
        assets = assets,
    )

    private fun addMessageInternal(
        sessionId: String,
        messageData: MessageData,
        author: StoredUser?,
        recipient: StoredUser?,
        attachments: List<AttachmentData>,
        assets: List<AssetData>,
    ): StoredSession {
        logger.debug(
            "Adding message {} to session {} ({} attachments, {} assets)",
            messageData.messageId, sessionId, attachments.size, assets.size,
        )

        // Verify the session exists and advance its activity in a single statement — MERGE on
        // SessionRef would silently create a bare node. Folding the two saves a round trip on
        // the hottest write path, and both are writes in this transaction, so a failure below
        // rolls the timestamp back along with the message.
        if (!touchSession(sessionId, clock.instant())) {
            throw IllegalArgumentException("Session not found: $sessionId")
        }

        val newMessage = NewMessageInSession(
            session = SessionRef(sessionId = sessionId),
            message = AttributedMessage(
                message = messageData,
                author = author?.let { UserRef(it.id) },
                recipient = recipient?.let { UserRef(it.id) },
                attachments = attachments,
                assets = assets,
            )
        )
        graphObjectManager.save(newMessage, CascadeType.PRESERVE)

        return findBySessionId(sessionId).orElseThrow {
            IllegalStateException("Session not found after adding message: $sessionId")
        }
    }

    @Transactional(readOnly = true)
    override fun getMessages(sessionId: String): List<SimpleStoredMessage> {
        // Messages are returned in chronological order via @SortedBy on StoredSession.messages
        return findBySessionId(sessionId).orElse(null)?.messages ?: emptyList()
    }

    @Transactional(readOnly = true)
    override fun getParticipants(sessionId: String): List<StoredUser> {
        // SessionParticipants uses @GraphPath (HAS_MESSAGE -> AUTHORED_BY) to map the
        // distinct authors directly, without materialising the message nodes.
        return graphObjectManager.load<SessionParticipants>(sessionId)?.participants ?: emptyList()
    }

    @Transactional
    override fun updateMessageNarration(sessionId: String, messageId: String, narration: String): Boolean {
        val updated = persistenceManager.getOne(
            QuerySpecification
                .withStatement(
                    """
                    MATCH (:ChatSession {sessionId: ${'$'}sessionId})
                          -[:HAS_MESSAGE]->(message:StoredMessage {messageId: ${'$'}messageId})
                    SET message.narration = ${'$'}narration
                    RETURN count(message) AS updated
                    """.trimIndent()
                )
                .bind(mapOf("sessionId" to sessionId, "messageId" to messageId, "narration" to narration))
                .map { (it as Number).toLong() }
        )
        if (updated == 0L) {
            logger.warn("Cannot update narration: message {} not found in session {}", messageId, sessionId)
            return false
        }
        logger.debug("Updated narration for message {} in session {}", messageId, sessionId)
        return true
    }

    @Deprecated(
        "Guesses which message to narrate; use the messageId-keyed overload",
        ReplaceWith("updateMessageNarration(conversationId, messageId, narration)"),
    )
    @Transactional
    override fun updateMessageNarration(conversationId: String, narration: String) {
        // Client-side filtering required: the DSL WHERE clause filters which sessions match,
        // but still loads all messages within each matched session.
        val session = findBySessionId(conversationId).orElse(null)
        if (session == null) {
            logger.warn("Cannot update narration: session not found for {}", conversationId)
            return
        }
        val latestAssistant = session.messages
            .filter { it.role == MessageRole.ASSISTANT && it.narration == null }
            .maxByOrNull { it.message.createdAt }
        if (latestAssistant == null) {
            logger.debug("No un-narrated assistant message found in session {}", conversationId)
            return
        }
        val updated = latestAssistant.copy(
            message = latestAssistant.message.copy(narration = narration)
        )
        graphObjectManager.save(updated)
        logger.debug("Updated narration for message {} in session {}", latestAssistant.messageId, conversationId)
    }

    @Transactional
    override fun deleteAll() {
        graphObjectManager.deleteAll<StoredSession> { }
    }

    /**
     * Advance the session's activity timestamp, returning whether the session exists.
     *
     * The timestamp only ever moves forward, so clock skew between application instances can
     * leave the ordering slightly stale but can never move a session backwards — which would
     * otherwise let a keyset walk skip or repeat it.
     */
    private fun touchSession(sessionId: String, activityAt: Instant): Boolean = persistenceManager.getOne(
        QuerySpecification
            .withStatement(
                """
                MATCH (session:ChatSession {sessionId: ${'$'}sessionId})
                SET session.lastActivityAt = CASE
                    WHEN session.lastActivityAt IS NULL OR session.lastActivityAt < ${'$'}activityAt
                    THEN ${'$'}activityAt ELSE session.lastActivityAt END
                RETURN count(session) AS found
                """.trimIndent()
            )
            .bind(mapOf("sessionId" to sessionId, "activityAt" to activityAt))
            .map { (it as Number).toLong() }
    ) > 0

    private fun <T> loadSessionPage(
        page: SessionPageRequest,
        loader: () -> List<T>,
        sessionData: (T) -> SessionData,
    ): SessionPage<T> {
        val loaded = loader()
        val items = loaded.take(page.pageSize)
        val nextCursor = if (loaded.size > page.pageSize) {
            items.lastOrNull()?.let(sessionData)?.let {
                SessionCursorCodec.encode(SessionPaging.cursorFor(page.order, it))
            }
        } else null
        return SessionPage(items, nextCursor)
    }

    /** One row of the re-embed scan: the message to rewrite, and the text to rewrite it from. */
    /**
     * One page row, projected as a MAP and not as two columns.
     *
     * `transform(Class)` deserializes ONE value per row, so `RETURN a, b` hands Jackson the row as
     * an array and it fails with "Cannot deserialize ... from Array value". A map literal is the
     * shape it expects. The error surfaced only against a real database, which is why
     * [ChatSessionReembedIntegrationTest] talks to one.
     */
    data class MessageText(val messageId: String, val content: String)

    private companion object {
        /**
         * Messages per embedding call and per write batch.
         *
         * One page is held in memory and sent as one request, so this bounds both. 200 matches
         * what DrivineStore uses for chunks; nothing here is tuned to a measurement.
         */
        const val REEMBED_PAGE_SIZE = 200
    }

}
