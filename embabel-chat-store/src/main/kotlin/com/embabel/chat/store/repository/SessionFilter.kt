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
 * Which of a user's sessions a listing returns.
 *
 * @param kind only sessions of this kind; null for every kind. A filter for
 * [SessionData.DEFAULT_KIND] also returns sessions stored before kinds existed
 * @param includeParts also return sessions that are part of another. Off by default, so a
 * thread's side conversations don't crowd the owner's list
 */
data class SessionFilter @JvmOverloads constructor(
    val kind: String? = null,
    val includeParts: Boolean = false,
) {
    init {
        require(kind == null || kind.isNotBlank()) { "kind must be null or non-blank" }
    }

    /** Whether a session with this [data], part of [partOf] (or of nothing), passes the filter. */
    fun matches(data: SessionData, partOf: String?): Boolean =
        (includeParts || partOf == null) && (kind == null || data.kind == kind)

    companion object {
        /** Every top-level session, of any kind: what a listing returns when not asked otherwise. */
        @JvmField
        val TOP_LEVEL = SessionFilter()
    }
}
