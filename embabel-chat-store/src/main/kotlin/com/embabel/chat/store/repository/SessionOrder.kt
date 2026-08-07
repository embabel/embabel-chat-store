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
 * The order in which sessions are listed, and correspondingly the keyset a page cursor
 * seeks on. A cursor is only valid for the order that produced it.
 */
enum class SessionOrder {

    /**
     * Newest-created first.
     *
     * Keys on `sessionId` alone. A UUIDv7 session ID embeds its creation timestamp in the
     * leading bits, and its canonical string form is big-endian hex, so lexicographic
     * comparison is chronological comparison. The ID is unique, so this is already a total
     * order and needs no tie-breaker, and it is backed by the index behind the `sessionId`
     * uniqueness constraint.
     *
     * Creation order is immutable and always present, so this ordering is unaffected by the
     * `lastActivityAt` backfill and is the default. A non-UUIDv7 session ID still paginates
     * deterministically, just not chronologically.
     */
    CREATED,

    /**
     * Most recently active first, `sessionId` descending as tie-breaker.
     *
     * Activity advances only when a message is added; enrichment such as narration does not
     * reorder sessions.
     *
     * Requires `lastActivityAt` to be materialised in the graph on every session. A value
     * supplied only by the model's `@Default` is not enough: it satisfies hydration but not
     * the keyset comparison, so such a session would still drop out of every page after the
     * first. [SessionActivityMigration] backfills installations that predate this ordering.
     */
    LAST_ACTIVITY,
}
