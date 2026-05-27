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
 * State of an existing vector index as read from the graph database.
 *
 * Compare against a [VectorIndexConfig] (via [matches]) to detect drift — most commonly
 * a change in the embedding model's dimensions that would render previously-stored
 * vectors unusable with the current configuration.
 *
 * @param name index name as reported by the database; null for databases that don't
 *   carry a user-supplied name (FalkorDB)
 * @param label the node label the index covers
 * @param property the node property holding the vector
 * @param dimensions the vector dimension the index was created with
 * @param similarityFunction the distance metric the index was created with
 */
data class VectorIndexInfo(
    val name: String?,
    val label: String,
    val property: String,
    val dimensions: Int,
    val similarityFunction: SimilarityFunction,
) {
    /**
     * Does this index match the dimensions and similarity function of [config]?
     * Names are not compared — a renamed index covering the same data is still a match.
     */
    fun matches(config: VectorIndexConfig): Boolean =
        label == config.label &&
            property == config.property &&
            dimensions == config.dimensions &&
            similarityFunction == config.similarityFunction
}