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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class SessionCursorCodecTest {

    @Test
    fun `cursor round trips timestamp precision and arbitrary session IDs`() {
        val cursor = SessionCursor(Instant.parse("2026-08-04T12:34:56.123456789Z"), "not-a-uuid/✓")

        assertEquals(cursor, SessionCursorCodec.decode(SessionCursorCodec.encode(cursor)))
    }

    @Test
    fun `malformed cursors fail with a stable public error`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            SessionCursorCodec.decode("not a cursor")
        }

        assertEquals("Invalid session cursor", exception.message)
    }

    @Test
    fun `page request enforces safe bounds`() {
        assertThrows(IllegalArgumentException::class.java) { SessionPageRequest(pageSize = 0) }
        assertThrows(IllegalArgumentException::class.java) { SessionPageRequest(pageSize = 101) }
        assertThrows(IllegalArgumentException::class.java) { SessionPageRequest(cursor = " ") }
    }
}
