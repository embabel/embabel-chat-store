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
 * A message was saved WITHOUT its vector, because embedding it failed.
 *
 * The message is not lost — a chat message outweighs its vector, so it is written anyway — but it
 * is missing from vector search until something embeds it. It is stored with a null
 * `embeddingModel`, which is what `ChatSessionRepository.reembedMessages` selects on
 * (`coalesce(embeddingModel, '') <> model`). So the retry is a re-embed run with the current
 * model — `MessageReembedder.reembed(embeddingService)` from the autoconfiguration — scheduled
 * whenever these events say one is worth doing. A run only embeds what is still pending, so running
 * it after a burst of these is cheap.
 *
 * Published once the message has been written, through Spring's `ApplicationEventPublisher`, like
 * the other chat store events.
 *
 * @param sessionId the session the message belongs to
 * @param messageId the message saved without a vector
 * @param role the message's role
 * @param error why embedding failed
 */
data class MessageEmbeddingFailedEvent @JvmOverloads constructor(
    val sessionId: String,
    val messageId: String,
    val role: MessageRole,
    val error: Throwable,
    override val timestamp: Instant = Instant.now(),
) : ChatStoreEvent
