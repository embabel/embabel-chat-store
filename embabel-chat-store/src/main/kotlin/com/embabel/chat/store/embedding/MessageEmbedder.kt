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

/**
 * Strategy for generating a vector embedding from a chat message.
 *
 * Implementations are graph-database-agnostic: they produce a vector and a model
 * identifier; storage on the graph is handled separately by the chat-store layer
 * as plain node properties (a list of floats and a string), which works uniformly
 * across Neo4j, FalkorDB, and Memgraph.
 *
 * Return `null` from [embed] to skip embedding for a particular message. This is
 * how decorators such as [RoleFilteringMessageEmbedder] opt messages out without
 * the caller needing to know.
 */
interface MessageEmbedder {

    /**
     * Compute an embedding for [message], or return `null` to skip.
     *
     * Implementations should not throw on transient failures unless the caller is
     * expected to handle them; in chat-store, embedding failures are caught and
     * the message is persisted without an embedding so messages are never lost.
     */
    suspend fun embed(message: Message): EmbeddingResult?
}