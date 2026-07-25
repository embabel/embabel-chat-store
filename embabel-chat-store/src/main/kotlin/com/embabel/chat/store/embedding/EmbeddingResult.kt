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

/**
 * The output of [MessageEmbedder.embed].
 *
 * @param vector the dense vector representation of the message content
 * @param model identifier of the model that produced [vector] — stored alongside
 *   the vector so consumers can detect when stored embeddings were produced by a
 *   different model from the one currently configured
 */
data class EmbeddingResult(
    val vector: FloatArray,
    val model: String,
) {
    // Data classes with a FloatArray field have reference-equality on that field;
    // override to compare vectors structurally so EmbeddingResult behaves intuitively.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddingResult) return false
        return model == other.model && vector.contentEquals(other.vector)
    }

    override fun hashCode(): Int = 31 * model.hashCode() + vector.contentHashCode()
}
