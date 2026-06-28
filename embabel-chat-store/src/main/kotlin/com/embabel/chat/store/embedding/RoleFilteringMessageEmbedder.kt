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
package com.embabel.chat.store.embedding

import com.embabel.chat.Message
import com.embabel.chat.MessageRole

/**
 * Decorator that restricts embedding to a configurable set of [MessageRole]s and
 * skips blank-content messages.
 *
 * The default role set is `USER` + `ASSISTANT` — system messages are usually
 * boilerplate and rarely useful for semantic retrieval.
 *
 * @param delegate the embedder that actually computes embeddings
 * @param roles the message roles to embed; messages with any other role return null
 */
class RoleFilteringMessageEmbedder(
    private val delegate: MessageEmbedder,
    private val roles: Set<MessageRole> = setOf(MessageRole.USER, MessageRole.ASSISTANT),
) : MessageEmbedder {

    override suspend fun embed(message: Message): EmbeddingResult? {
        if (message.role !in roles) return null
        if (message.content.isBlank()) return null
        return delegate.embed(message)
    }
}
