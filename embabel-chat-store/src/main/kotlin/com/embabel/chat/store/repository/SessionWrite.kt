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
import com.embabel.chat.store.model.StoredUser

/**
 * What a new session is: its kind, the session it is part of, and who it is held with. All three
 * are set when the session is created and never change.
 *
 * @param kind what kind of session this is, such as `chat` or `thread`; see [SessionData.kind]
 * @param partOf the ID of the session this one is part of. That session must exist, must itself be
 * top-level, and must be owned by the same user; null for a top-level session
 * @param heldWith the one user the session is held with, such as the agent a side conversation is with
 * @see ChatSessionRepository.createSession
 * @see ChatSessionRepository.createSessionWithMessage
 */
data class SessionWrite @JvmOverloads constructor(
    val kind: String = SessionData.DEFAULT_KIND,
    val partOf: String? = null,
    val heldWith: StoredUser? = null,
) {
    init {
        require(kind.isNotBlank()) { "kind must not be blank" }
        require(partOf == null || partOf.isNotBlank()) { "partOf must be null or non-blank" }
    }

    companion object {
        /** A top-level session of the default kind, held with no one: what a session was before kinds existed. */
        @JvmField
        val DEFAULT = SessionWrite()
    }
}
