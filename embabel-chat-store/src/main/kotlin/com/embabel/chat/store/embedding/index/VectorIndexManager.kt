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
 * Database-agnostic management of a vector index over a node label and property.
 *
 * Implementations exist per graph-database flavour (Neo4j, Memgraph, FalkorDB). They
 * differ in DDL and introspection syntax but expose a uniform contract:
 *
 * - [ensureIndex] is **idempotent**. Call it on every startup; if a matching index
 *   exists it does nothing, if no index exists it creates one, and if an index exists
 *   with a different shape it returns without dropping anything so the caller can decide.
 * - [recreateIndex] is **destructive**. It drops any existing index covering the
 *   same label/property and creates a new one matching [VectorIndexConfig]. Use this
 *   when the embedding model has changed and previously-stored vectors are now stale —
 *   callers are responsible for re-embedding existing data; this method does not.
 * - [findIndex] inspects the database for an existing index covering the given
 *   label/property and returns its current shape, or null.
 */
interface VectorIndexManager {

    /**
     * Idempotently ensure a vector index exists matching [config].
     *
     * @return the resulting state, describing what the call did
     */
    fun ensureIndex(config: VectorIndexConfig): EnsureResult

    /**
     * Drop any existing index covering `config.label` / `config.property` and create
     * a new one matching [config]. Destructive — callers should ensure they want this.
     */
    fun recreateIndex(config: VectorIndexConfig)

    /**
     * Inspect the database for an existing vector index on [label] / [property].
     * Returns null if no such index exists.
     */
    fun findIndex(label: String, property: String): VectorIndexInfo?

    /**
     * Drop the vector index identified by [label] / [property], if one exists.
     * No-op if no matching index is present.
     */
    fun dropIndex(label: String, property: String)

    /**
     * Outcome of [ensureIndex].
     */
    sealed interface EnsureResult {
        /** No prior index existed; one was created matching the requested config. */
        data class Created(val info: VectorIndexInfo) : EnsureResult

        /** A matching index already existed; nothing was changed. */
        data class AlreadyMatching(val info: VectorIndexInfo) : EnsureResult

        /**
         * An index already exists on the same label/property but with a different
         * shape (e.g. different dimensions). Nothing was changed — caller must
         * explicitly [recreateIndex] (and re-embed data) to resolve this.
         */
        data class Drift(val existing: VectorIndexInfo, val requested: VectorIndexConfig) : EnsureResult
    }
}