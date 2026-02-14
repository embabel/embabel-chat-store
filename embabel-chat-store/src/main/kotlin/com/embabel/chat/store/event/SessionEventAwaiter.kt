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
package com.embabel.chat.store.event

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Bridges [SessionCreatedEvent] to coroutine suspension, allowing async code
 * to wait for a session to be created before proceeding.
 *
 * This is used by [com.embabel.chat.store.adapter.StoredConversation] to handle
 * the race condition where message persistence may be attempted before the
 * backing session exists in the database.
 *
 * ## Usage
 *
 * ```kotlin
 * // Register interest before the operation that might fail
 * val signal = awaiter.register(sessionId)
 * try {
 *     repository.addMessage(sessionId, ...)
 * } catch (e: IllegalArgumentException) {
 *     // Session not found - wait for it to be created
 *     awaiter.awaitSession(signal, timeout = 10.seconds)
 *     repository.addMessage(sessionId, ...) // retry
 * } finally {
 *     awaiter.unregister(sessionId, signal)
 * }
 * ```
 */
class SessionEventAwaiter {

    private val logger = LoggerFactory.getLogger(SessionEventAwaiter::class.java)

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    @EventListener
    fun onSessionCreated(event: SessionCreatedEvent) {
        val sessionId = event.session.session.sessionId
        pending.remove(sessionId)?.let { deferred ->
            logger.debug("Session {} created, resuming awaiter", sessionId)
            deferred.complete(Unit)
        }
    }

    /**
     * Register interest in a session being created.
     * Call this BEFORE the operation that might fail, to avoid missing the event.
     *
     * @return the signal to pass to [awaitSession] and [unregister]
     */
    fun register(sessionId: String): CompletableDeferred<Unit> {
        return pending.computeIfAbsent(sessionId) { CompletableDeferred() }
    }

    /**
     * Suspend until the session is created or the timeout expires.
     *
     * @param signal the signal returned by [register]
     * @param timeout maximum time to wait
     * @throws kotlinx.coroutines.TimeoutCancellationException if the session is not created within the timeout
     */
    suspend fun awaitSession(signal: CompletableDeferred<Unit>, timeout: Duration = DEFAULT_TIMEOUT) {
        withTimeout(timeout) { signal.await() }
    }

    /**
     * Clean up a registration if it was not used (e.g., the first attempt succeeded).
     */
    fun unregister(sessionId: String, signal: CompletableDeferred<Unit>) {
        pending.remove(sessionId, signal)
    }

    companion object {
        val DEFAULT_TIMEOUT: Duration = 10.seconds
    }
}