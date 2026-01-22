package com.embabel.chat.store.repository

import com.embabel.chat.store.model.MessageData
import com.embabel.chat.store.model.SessionData
import com.embabel.chat.store.model.SessionUser
import com.embabel.chat.store.model.StoredMessage
import com.embabel.chat.store.model.StoredSession
import org.drivine.manager.GraphObjectManager
import org.slf4j.LoggerFactory
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.Optional

/**
 * Drivine-based implementation of ChatSessionRepository.
 *
 * Uses GraphObjectManager for type-safe graph operations.
 * Note: Full DSL support requires code generation. This implementation
 * uses basic GraphObjectManager operations.
 */
open class ChatSessionRepositoryImpl(
    private val graphObjectManager: GraphObjectManager
) : ChatSessionRepository {

    private val logger = LoggerFactory.getLogger(ChatSessionRepositoryImpl::class.java)

    @Transactional
    override fun createSession(sessionId: String, owner: SessionUser, title: String?): StoredSession {
        logger.debug("Creating session {} for owner {}", sessionId, owner.id)

        val session = StoredSession(
            session = SessionData(
                sessionId = sessionId,
                title = title,
                createdAt = Instant.now()
            ),
            owner = owner,
            messages = emptyList()
        )

        return graphObjectManager.save(session)
    }

    @Transactional
    override fun createSessionWithMessage(
        sessionId: String,
        owner: SessionUser,
        title: String?,
        messageData: MessageData,
        messageAuthor: SessionUser?
    ): StoredSession {
        logger.debug("Creating session {} with initial message for owner {}", sessionId, owner.id)

        val message = StoredMessage(
            message = messageData,
            author = messageAuthor
        )

        val session = StoredSession(
            session = SessionData(
                sessionId = sessionId,
                title = title,
                createdAt = Instant.now()
            ),
            owner = owner,
            messages = listOf(message)
        )

        return graphObjectManager.save(session)
    }

    @Transactional(readOnly = true)
    override fun findBySessionId(sessionId: String): Optional<StoredSession> {
        val results = graphObjectManager.loadAll(StoredSession::class.java)
            .filter { it.session.sessionId == sessionId }
        return Optional.ofNullable(results.firstOrNull())
    }

    @Transactional(readOnly = true)
    override fun listSessionsForUser(userId: String): List<StoredSession> {
        return graphObjectManager.loadAll(StoredSession::class.java)
            .filter { it.owner.id == userId }
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
        graphObjectManager.delete(sessionId, StoredSession::class.java)
    }

    @Transactional
    override fun addMessage(sessionId: String, messageData: MessageData, author: SessionUser?): StoredSession {
        logger.debug("Adding message {} to session {}", messageData.messageId, sessionId)

        val session = findBySessionId(sessionId).orElseThrow {
            IllegalArgumentException("Session not found: $sessionId")
        }

        val message = StoredMessage(
            message = messageData,
            author = author
        )

        val updated = session.withMessage(message)
        return graphObjectManager.save(updated)
    }

    @Transactional(readOnly = true)
    override fun getMessages(sessionId: String): List<StoredMessage> {
        val session = findBySessionId(sessionId).orElse(null)
        return session?.messages?.sortedBy { it.messageId } ?: emptyList()
    }

    @Transactional
    override fun deleteAll() {
        graphObjectManager.loadAll(StoredSession::class.java).forEach {
            graphObjectManager.delete(it.session.sessionId, StoredSession::class.java)
        }
    }
}
