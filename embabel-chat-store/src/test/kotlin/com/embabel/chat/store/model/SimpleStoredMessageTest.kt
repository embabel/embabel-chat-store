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
package com.embabel.chat.store.model

import com.embabel.chat.MessageRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class SimpleStoredMessageTest {

    private val data = MessageData("m-1", MessageRole.USER, "hi", Instant.now())
    private val alice = TestSessionUser("alice", "Alice")
    private val bob = TestSessionUser("bob", "Bob")

    @Suppress("DEPRECATION")
    @Test
    fun `the single-recipient constructor holds its recipient, or none when given null`() {
        val toBob = SimpleStoredMessage(data, alice, bob)
        val toNobody = SimpleStoredMessage(data, alice, null)

        assertEquals(listOf(bob), toBob.recipients)
        assertEquals(bob, toBob.recipient)
        assertEquals(emptyList<StoredUser>(), toNobody.recipients)
        assertNull(toNobody.recipient)
    }

    @Test
    fun `recipient is the first of the recipients, and null when there are none`() {
        assertEquals(alice, SimpleStoredMessage(data, recipients = listOf(alice, bob)).recipient)
        assertNull(SimpleStoredMessage(data).recipient)
    }
}
