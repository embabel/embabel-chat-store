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

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

internal data class SessionCursor(
    val lastActivityAt: Instant,
    val sessionId: String,
)

/** Versioned binary cursor encoding; callers treat the resulting Base64URL value as opaque. */
internal object SessionCursorCodec {
    private const val VERSION: Byte = 1
    private const val HEADER_SIZE = 1 + Long.SIZE_BYTES + Int.SIZE_BYTES + Int.SIZE_BYTES
    private const val MAX_SESSION_ID_BYTES = 16 * 1024

    fun encode(cursor: SessionCursor): String {
        val idBytes = cursor.sessionId.toByteArray(StandardCharsets.UTF_8)
        require(idBytes.size <= MAX_SESSION_ID_BYTES) { "sessionId is too large to encode in a cursor" }
        val bytes = ByteBuffer.allocate(HEADER_SIZE + idBytes.size)
            .put(VERSION)
            .putLong(cursor.lastActivityAt.epochSecond)
            .putInt(cursor.lastActivityAt.nano)
            .putInt(idBytes.size)
            .put(idBytes)
            .array()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun decode(encoded: String): SessionCursor {
        try {
            val bytes = Base64.getUrlDecoder().decode(encoded)
            require(bytes.size >= HEADER_SIZE) { "cursor payload is truncated" }
            val buffer = ByteBuffer.wrap(bytes)
            require(buffer.get() == VERSION) { "unsupported cursor version" }
            val epochSecond = buffer.long
            val nano = buffer.int
            require(nano in 0..999_999_999) { "invalid cursor timestamp" }
            val idLength = buffer.int
            require(idLength in 0..MAX_SESSION_ID_BYTES && idLength == buffer.remaining()) {
                "invalid cursor session ID length"
            }
            val idBytes = ByteArray(idLength)
            buffer.get(idBytes)
            val sessionId = String(idBytes, StandardCharsets.UTF_8)
            require(sessionId.isNotEmpty()) { "cursor session ID is empty" }
            return SessionCursor(Instant.ofEpochSecond(epochSecond, nano.toLong()), sessionId)
        } catch (cause: Exception) {
            if (cause is IllegalArgumentException && cause.message == "Invalid session cursor") throw cause
            throw IllegalArgumentException("Invalid session cursor", cause)
        }
    }
}
