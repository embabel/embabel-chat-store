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

import java.time.Instant

/**
 * The decoded position of a page boundary: the keyset values of the last item returned.
 *
 * One variant per [SessionOrder], because the keyset differs. Callers see only the opaque
 * encoded form produced by [SessionCursorCodec]; the [order] is carried so that a cursor
 * issued under one ordering is rejected rather than silently seeking on the wrong key.
 */
internal sealed interface SessionCursor {

    val order: SessionOrder

    val sessionId: String

    /** Position in [SessionOrder.CREATED]: the session ID is the whole keyset. */
    data class Created(
        override val sessionId: String,
    ) : SessionCursor {
        override val order: SessionOrder get() = SessionOrder.CREATED
    }

    /** Position in [SessionOrder.LAST_ACTIVITY]: activity timestamp, session ID as tie-breaker. */
    data class LastActivity(
        val lastActivityAt: Instant,
        override val sessionId: String,
    ) : SessionCursor {
        override val order: SessionOrder get() = SessionOrder.LAST_ACTIVITY
    }
}
