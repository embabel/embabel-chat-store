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

import com.embabel.chat.store.model.StoredSession
import com.embabel.common.core.types.Timestamped
import java.time.Instant

/**
 * Base interface for chat store session events.
 *
 * Note: Message events are defined in embabel-agent-api at [com.embabel.chat.event.MessageEvent].
 * These session events are specific to the chat store persistence layer.
 */
sealed interface ChatStoreEvent : Timestamped

/**
 * Event published when a new chat session is created.
 *
 * @param session the created session
 * @param timestamp when the event occurred
 */
data class SessionCreatedEvent(
    val session: StoredSession,
    override val timestamp: Instant = Instant.now()
) : ChatStoreEvent

/**
 * Event published when a session is deleted.
 *
 * @param sessionId the ID of the deleted session
 * @param timestamp when the event occurred
 */
data class SessionDeletedEvent(
    val sessionId: String,
    override val timestamp: Instant = Instant.now()
) : ChatStoreEvent

/**
 * Event published when a session title is updated.
 *
 * @param sessionId the session ID
 * @param newTitle the new title
 * @param timestamp when the event occurred
 */
data class SessionTitleUpdatedEvent(
    val sessionId: String,
    val newTitle: String,
    override val timestamp: Instant = Instant.now()
) : ChatStoreEvent
