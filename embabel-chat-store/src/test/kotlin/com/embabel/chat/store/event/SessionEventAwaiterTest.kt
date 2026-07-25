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

import com.embabel.chat.store.model.SessionData
import com.embabel.chat.store.model.StoredSession
import com.embabel.chat.store.model.TestSessionUser
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SessionEventAwaiterTest {

    private val awaiter = SessionEventAwaiter()

    private fun createStoredSession(sessionId: String): StoredSession {
        return StoredSession(
            session = SessionData(
                sessionId = sessionId,
                title = "Test",
                createdAt = Instant.now()
            ),
            owner = TestSessionUser(
                id = UUID.randomUUID().toString(),
                displayName = "Test User"
            ),
            messages = emptyList()
        )
    }

    @Test
    fun `signal completes immediately when event fires before await`() = runBlocking {
        val sessionId = UUID.randomUUID().toString()
        val signal = awaiter.register(sessionId)

        // Event fires before await
        awaiter.onSessionCreated(SessionCreatedEvent(createStoredSession(sessionId)))

        // Should complete immediately
        withTimeout(1.seconds) {
            awaiter.awaitSession(signal)
        }
    }

    @Test
    fun `signal completes when event fires after await`() = runBlocking {
        val sessionId = UUID.randomUUID().toString()
        val signal = awaiter.register(sessionId)

        // Start awaiting in a coroutine
        val job = launch {
            awaiter.awaitSession(signal, timeout = 5.seconds)
        }

        // Fire event after a short delay
        awaiter.onSessionCreated(SessionCreatedEvent(createStoredSession(sessionId)))

        // Job should complete
        withTimeout(1.seconds) {
            job.join()
        }
        assertFalse(job.isCancelled)
    }

    @Test
    fun `timeout throws when event never fires`() = runBlocking {
        val sessionId = UUID.randomUUID().toString()
        val signal = awaiter.register(sessionId)

        assertThrows<kotlinx.coroutines.TimeoutCancellationException> {
            runBlocking {
                awaiter.awaitSession(signal, timeout = 100.milliseconds)
            }
        }

        awaiter.unregister(sessionId, signal)
    }

    @Test
    fun `unregister cleans up unused signal`() = runBlocking {
        val sessionId = UUID.randomUUID().toString()
        val signal = awaiter.register(sessionId)

        awaiter.unregister(sessionId, signal)

        // Event after unregister should not complete the signal
        awaiter.onSessionCreated(SessionCreatedEvent(createStoredSession(sessionId)))
        assertFalse(signal.isCompleted)
    }

    @Test
    fun `event for different session does not complete signal`() = runBlocking {
        val sessionId = UUID.randomUUID().toString()
        val otherSessionId = UUID.randomUUID().toString()
        val signal = awaiter.register(sessionId)

        awaiter.onSessionCreated(SessionCreatedEvent(createStoredSession(otherSessionId)))

        assertFalse(signal.isCompleted)

        awaiter.unregister(sessionId, signal)
    }

    @Test
    fun `multiple registrations for same session share signal`() = runBlocking {
        val sessionId = UUID.randomUUID().toString()
        val signal1 = awaiter.register(sessionId)
        val signal2 = awaiter.register(sessionId)

        // computeIfAbsent returns the same deferred
        assertTrue(signal1 === signal2)

        awaiter.onSessionCreated(SessionCreatedEvent(createStoredSession(sessionId)))
        assertTrue(signal1.isCompleted)
    }
}
