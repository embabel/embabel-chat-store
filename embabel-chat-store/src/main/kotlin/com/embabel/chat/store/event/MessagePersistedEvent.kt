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

import com.embabel.chat.MessageRole
import java.time.Instant

/**
 * Published once a message has been written to the database, carrying the ID it was stored
 * under.
 *
 * [com.embabel.chat.event.MessageEvent] conveys the same lifecycle but only ever carries a
 * [com.embabel.chat.Message], which has no identity, so a listener has no way to address the
 * message it was just told about. This event exists to close that gap: it is the point at
 * which enrichment keyed by message ID — narration, for instance — is safe, because the
 * message is known to be persisted rather than merely queued.
 *
 * It also names everyone the message was sent to. [com.embabel.chat.event.MessageEvent] has room
 * for one recipient, and carries the first, so a listener routing a multi-party message to each
 * recipient reads [recipientIds] here.
 *
 * @param sessionId the session the message belongs to
 * @param messageId the ID the message was stored under
 * @param role the role of the persisted message, so listeners can filter without a read
 * @param timestamp when the event occurred
 * @param authorId the ID of the message's author, or null for a message with none
 * @param recipientIds the IDs of everyone the message was sent to, in the order they were given
 */
data class MessagePersistedEvent @JvmOverloads constructor(
    val sessionId: String,
    val messageId: String,
    val role: MessageRole,
    override val timestamp: Instant = Instant.now(),
    val authorId: String? = null,
    val recipientIds: List<String> = emptyList(),
) : ChatStoreEvent
