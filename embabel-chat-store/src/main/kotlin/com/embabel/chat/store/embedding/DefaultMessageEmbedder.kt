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
import com.embabel.common.ai.model.EmbeddingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Default [MessageEmbedder], backed by an embabel [EmbeddingService].
 *
 * The provider behind the [EmbeddingService] is an implementation detail of embabel —
 * an app might be using Onyx, OpenAI directly, Spring AI, or anything else embabel
 * exposes through this abstraction. The [EmbeddingService.name] is stored alongside
 * each vector so consumers can detect when stored vectors were produced by a different
 * model from the one currently configured.
 *
 * Embedding calls are blocking I/O; this implementation switches to [Dispatchers.IO]
 * so it stays well-behaved regardless of the dispatcher in effect at the call site.
 *
 * @param embeddingService the embabel embedding service used to compute vectors
 */
class DefaultMessageEmbedder(
    private val embeddingService: EmbeddingService,
) : MessageEmbedder {

    override suspend fun embed(message: Message): EmbeddingResult? {
        if (message.content.isBlank()) return null
        val vector = withContext(Dispatchers.IO) { embeddingService.embed(message.content) }
        return EmbeddingResult(vector = vector, model = embeddingService.name)
    }
}