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

/** Request for one cursor-based page of sessions. */
data class SessionPageRequest(
    val pageSize: Int = DEFAULT_PAGE_SIZE,
    val cursor: String? = null,
) {
    init {
        require(pageSize in 1..MAX_PAGE_SIZE) {
            "pageSize must be between 1 and $MAX_PAGE_SIZE, was $pageSize"
        }
        require(cursor == null || cursor.isNotBlank()) { "cursor must be null or non-blank" }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 100
    }
}

/** A page of sessions and the opaque cursor for the following page, if one exists. */
data class SessionPage<T>(
    val items: List<T>,
    val nextCursor: String?,
)
