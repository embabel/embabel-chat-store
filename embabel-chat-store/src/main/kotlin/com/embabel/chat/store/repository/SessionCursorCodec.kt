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

/**
 * Versioned binary cursor encoding; callers treat the resulting Base64URL value as opaque.
 *
 * The payload carries the [SessionOrder] it was issued under. [decode] requires the caller to
 * state the order it is about to seek on and rejects a mismatch, because the two orderings
 * key on different properties: replaying a cursor against the wrong ordering would otherwise
 * seek on the wrong value and silently return a wrong page rather than an error.
 */
internal object SessionCursorCodec {
    private const val VERSION: Byte = 1
    private const val MAX_SESSION_ID_BYTES = 16 * 1024

    fun encode(cursor: SessionCursor): String {
        val idBytes = cursor.sessionId.toByteArray(StandardCharsets.UTF_8)
        require(idBytes.size <= MAX_SESSION_ID_BYTES) { "sessionId is too large to encode in a cursor" }
        val timestampSize = if (cursor is SessionCursor.LastActivity) Long.SIZE_BYTES + Int.SIZE_BYTES else 0
        val buffer = ByteBuffer.allocate(2 + timestampSize + Int.SIZE_BYTES + idBytes.size)
            .put(VERSION)
            .put(cursor.order.ordinal.toByte())
        if (cursor is SessionCursor.LastActivity) {
            buffer.putLong(cursor.lastActivityAt.epochSecond).putInt(cursor.lastActivityAt.nano)
        }
        val bytes = buffer.putInt(idBytes.size).put(idBytes).array()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun decode(encoded: String, expectedOrder: SessionOrder): SessionCursor {
        try {
            val buffer = ByteBuffer.wrap(Base64.getUrlDecoder().decode(encoded))
            require(buffer.remaining() >= 2) { "cursor payload is truncated" }
            require(buffer.get() == VERSION) { "unsupported cursor version" }
            val order = buffer.get().toInt().let { ordinal ->
                require(ordinal in SessionOrder.entries.indices) { "unknown cursor ordering" }
                SessionOrder.entries[ordinal]
            }
            require(order == expectedOrder) {
                "cursor was issued for $order but this request orders by $expectedOrder"
            }
            return when (order) {
                SessionOrder.CREATED -> SessionCursor.Created(readSessionId(buffer))
                SessionOrder.LAST_ACTIVITY -> {
                    require(buffer.remaining() >= Long.SIZE_BYTES + Int.SIZE_BYTES) { "cursor payload is truncated" }
                    val epochSecond = buffer.long
                    val nano = buffer.int
                    require(nano in 0..999_999_999) { "invalid cursor timestamp" }
                    SessionCursor.LastActivity(Instant.ofEpochSecond(epochSecond, nano.toLong()), readSessionId(buffer))
                }
            }
        } catch (cause: Exception) {
            throw IllegalArgumentException("Invalid session cursor", cause)
        }
    }

    private fun readSessionId(buffer: ByteBuffer): String {
        require(buffer.remaining() >= Int.SIZE_BYTES) { "cursor payload is truncated" }
        val idLength = buffer.int
        require(idLength in 1..MAX_SESSION_ID_BYTES && idLength == buffer.remaining()) {
            "invalid cursor session ID length"
        }
        val idBytes = ByteArray(idLength)
        buffer.get(idBytes)
        return String(idBytes, StandardCharsets.UTF_8)
    }
}
