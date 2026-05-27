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
package com.embabel.chat.store.embedding.index

/**
 * Desired configuration for a vector index.
 *
 * An index is identified across graph databases by [label] + [property]; the [name] is
 * the Neo4j / Memgraph index name and is ignored by FalkorDB (which has no concept of a
 * user-supplied index name). When [name] is left null, implementations derive a default
 * from the label and property.
 *
 * @param label the node label the index covers (e.g. `"StoredMessage"`)
 * @param property the node property holding the vector (e.g. `"embedding"`)
 * @param dimensions the vector dimension, taken from the embedding model
 * @param similarityFunction the distance metric used at query time
 * @param name explicit index name; defaults to `"${label}_${property}_vector"` if null
 */
data class VectorIndexConfig(
    val label: String,
    val property: String,
    val dimensions: Int,
    val similarityFunction: SimilarityFunction = SimilarityFunction.COSINE,
    val name: String? = null,
) {
    val effectiveName: String get() = name ?: "${label}_${property}_vector"
}