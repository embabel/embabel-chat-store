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
package com.embabel.chat.store.repository

/**
 * What a re-embed of stored messages did.
 *
 * A count alone could not say whether the vector index had to be rebuilt, which is the part an
 * operator wants in the log: rewriting vectors at the same width is routine, and doing it because
 * the model's shape moved is an event that also costs a window in which message search finds
 * nothing.
 *
 * @param messages how many messages were re-embedded — those already made by the current model are
 * skipped, so a second run reports zero
 * @param indexRecreated whether the message vector index was dropped and remade to match the
 * model's current shape
 */
data class MessageReembedReport(
    val messages: Int,
    val indexRecreated: Boolean,
)
