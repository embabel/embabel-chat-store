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
package com.embabel.chat.store.adapter

import com.embabel.chat.AssistantMessage
import com.embabel.chat.MessageRole
import com.embabel.chat.SystemMessage
import com.embabel.chat.UserMessage
import com.embabel.chat.store.model.MessageData
import com.embabel.chat.store.model.SimpleStoredMessage
import com.embabel.chat.store.model.StoredUser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Tests for MessageData and SimpleStoredMessage conversion methods.
 */
class MessageDataConversionTest {

    @Test
    fun `MessageData from UserMessage converts correctly`() {
        val timestamp = Instant.now()
        val message = UserMessage(
            content = "Hello, world!",
            timestamp = timestamp
        )

        val messageData = MessageData.from(message, "msg-123")

        assertEquals("msg-123", messageData.messageId)
        assertEquals(MessageRole.USER, messageData.role)
        assertEquals("Hello, world!", messageData.content)
        assertEquals(timestamp, messageData.createdAt)
    }

    @Test
    fun `MessageData from AssistantMessage converts correctly`() {
        val message = AssistantMessage(
            content = "I can help with that!"
        )

        val messageData = MessageData.from(message)

        assertEquals(MessageRole.ASSISTANT, messageData.role)
        assertEquals("I can help with that!", messageData.content)
    }

    @Test
    fun `MessageData from SystemMessage converts correctly`() {
        val message = SystemMessage(
            content = "System initialized"
        )

        val messageData = MessageData.from(message)

        assertEquals(MessageRole.SYSTEM, messageData.role)
        assertEquals("System initialized", messageData.content)
    }

    @Test
    fun `MessageData toMessage converts USER to UserMessage`() {
        val timestamp = Instant.now()
        val messageData = MessageData(
            messageId = "msg-123",
            role = MessageRole.USER,
            content = "Hello!",
            createdAt = timestamp
        )

        val message = messageData.toMessage()

        assertTrue(message is UserMessage)
        assertEquals(MessageRole.USER, message.role)
        assertEquals("Hello!", message.content)
        assertEquals(timestamp, message.timestamp)
    }

    @Test
    fun `MessageData toMessage converts ASSISTANT to AssistantMessage`() {
        val messageData = MessageData(
            messageId = "msg-123",
            role = MessageRole.ASSISTANT,
            content = "How can I help?",
            createdAt = Instant.now()
        )

        val message = messageData.toMessage()

        assertTrue(message is AssistantMessage)
        assertEquals(MessageRole.ASSISTANT, message.role)
        assertEquals("How can I help?", message.content)
    }

    @Test
    fun `MessageData toMessage converts SYSTEM to SystemMessage`() {
        val messageData = MessageData(
            messageId = "msg-123",
            role = MessageRole.SYSTEM,
            content = "Welcome to the chat",
            createdAt = Instant.now()
        )

        val message = messageData.toMessage()

        assertTrue(message is SystemMessage)
        assertEquals(MessageRole.SYSTEM, message.role)
        assertEquals("Welcome to the chat", message.content)
    }

    @Test
    fun `SimpleStoredMessage toMessage delegates to MessageData`() {
        val messageData = MessageData(
            messageId = "msg-123",
            role = MessageRole.USER,
            content = "Hello!",
            createdAt = Instant.now()
        )
        val simpleStoredMessage = SimpleStoredMessage(
            message = messageData,
            author = TestSessionUserForConversion("user-1", "Alice")
        )

        val message = simpleStoredMessage.toMessage()

        assertTrue(message is UserMessage)
        assertEquals(MessageRole.USER, message.role)
        assertEquals("Hello!", message.content)
    }

    @Test
    fun `SimpleStoredMessage implements StoredMessage interface`() {
        val timestamp = Instant.now()
        val messageData = MessageData(
            messageId = "msg-123",
            role = MessageRole.ASSISTANT,
            content = "Response",
            createdAt = timestamp
        )
        val simpleStoredMessage = SimpleStoredMessage(message = messageData)

        // Test StoredMessage interface properties
        assertEquals(MessageRole.ASSISTANT, simpleStoredMessage.role)
        assertEquals("Response", simpleStoredMessage.content)
        assertEquals(timestamp, simpleStoredMessage.timestamp)
    }

    @Test
    fun `MessageData from generates UUID when not provided`() {
        val message = UserMessage(content = "Test")

        val messageData = MessageData.from(message)

        assertNotNull(messageData.messageId)
        assertTrue(messageData.messageId.isNotBlank())
    }
}

/**
 * Simple test implementation of StoredUser for conversion tests.
 */
private data class TestSessionUserForConversion(
    override val id: String,
    override val displayName: String,
    override val username: String = displayName,
    override val email: String? = null
) : StoredUser
