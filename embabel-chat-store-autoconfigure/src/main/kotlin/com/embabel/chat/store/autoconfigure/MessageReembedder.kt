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
package com.embabel.chat.store.autoconfigure

import com.embabel.chat.store.repository.ChatSessionRepository
import com.embabel.chat.store.repository.MessageReembedReport
import com.embabel.common.ai.model.EmbeddingService
import org.drivine.schema.VectorIndexSpec

/**
 * Re-embeds stored messages with whatever embedding model the host is now using.
 *
 * WHY THIS EXISTS RATHER THAN THE HOST CALLING THE REPOSITORY. `reembedMessages` needs the message
 * vector index's spec, and that spec is described by [ChatStoreProperties] — which lives here,
 * where the startup catalog is built, and which a host has no business reading. Without this a
 * host would construct its own spec from its own reading of the same properties, and the index
 * made at boot and the index remade around a re-embed would be two declarations of one identity
 * that nothing keeps in step.
 *
 * So the spec is built ONCE, by [ChatStoreAutoConfiguration.chatStoreVectorIndexSchema] for
 * startup and by this for the re-embed, from the same function. The host supplies only what is
 * genuinely its own: which model it is now using, and how to turn text into a vector with it.
 */
class MessageReembedder(
    private val repository: ChatSessionRepository,
    private val specFor: (Int) -> VectorIndexSpec,
) {

    /**
     * Re-embed every message not already made by [embeddingService], and leave the index
     * describing what they now hold.
     *
     * Takes the SERVICE rather than a name and a width, because asking it twice invites the two to
     * disagree: a host that read the name before a swap and the width after would write one
     * model's name onto another model's vectors.
     */
    fun reembed(embeddingService: EmbeddingService): MessageReembedReport =
        repository.reembedMessages(
            modelName = embeddingService.name,
            spec = specFor(embeddingService.dimensions),
            embed = { texts -> embeddingService.embed(texts).map { vector -> vector.map(Float::toDouble) } },
        )
}
