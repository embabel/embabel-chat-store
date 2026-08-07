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

import com.embabel.chat.store.model.SessionData

/**
 * Client-side keyset paging over an already-ordered list.
 *
 * Backs the default [ChatSessionRepository] paging methods so that in-memory implementations
 * and test doubles inherit correct cursor semantics for free. It loads every session to return
 * one page, so a database-backed implementation should override those methods and push the
 * keyset into the query instead — as [ChatSessionRepositoryImpl] does.
 */
internal object SessionPaging {

    fun <T> inMemory(ordered: List<T>, page: SessionPageRequest, sessionData: (T) -> SessionData): SessionPage<T> {
        val cursor = page.cursor?.let { SessionCursorCodec.decode(it, page.order) }
        val remaining = if (cursor == null) {
            ordered
        } else {
            ordered.dropWhile { !isAfter(sessionData(it), cursor) }
        }
        val items = remaining.take(page.pageSize)
        val nextCursor = if (remaining.size > page.pageSize) {
            items.lastOrNull()?.let(sessionData)?.let { SessionCursorCodec.encode(cursorFor(page.order, it)) }
        } else null
        return SessionPage(items, nextCursor)
    }

    fun cursorFor(order: SessionOrder, data: SessionData): SessionCursor = when (order) {
        SessionOrder.CREATED -> SessionCursor.Created(data.sessionId)
        SessionOrder.LAST_ACTIVITY -> SessionCursor.LastActivity(data.lastActivityAt, data.sessionId)
    }

    /** Whether [data] falls strictly after [cursor] in the cursor's descending order. */
    private fun isAfter(data: SessionData, cursor: SessionCursor): Boolean = when (cursor) {
        is SessionCursor.Created -> data.sessionId < cursor.sessionId
        is SessionCursor.LastActivity -> when {
            data.lastActivityAt != cursor.lastActivityAt -> data.lastActivityAt < cursor.lastActivityAt
            else -> data.sessionId < cursor.sessionId
        }
    }
}
