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
import com.embabel.chat.Role
import com.embabel.chat.SystemMessage
import com.embabel.chat.UserMessage
import com.embabel.chat.store.model.MessageData
import com.embabel.chat.store.model.StoredMessage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class MessageMapperTest {

    @Test
    fun `toMessageData converts UserMessage correctly`() {
        val timestamp = Instant.now()
        val message = UserMessage(
            content = "Hello, world!",
            name = "Alice",
            timestamp = timestamp
        )

        val messageData = MessageMapper.toMessageData(message, "msg-123")

        assertEquals("msg-123", messageData.messageId)
        assertEquals(Role.USER, messageData.role)
        assertEquals("Hello, world!", messageData.content)
        assertEquals(timestamp, messageData.createdAt)
        assertEquals("Alice", messageData.metadata?.get("senderName"))
    }

    @Test
    fun `toMessageData converts AssistantMessage correctly`() {
        val message = AssistantMessage(content = "I can help with that!")

        val messageData = MessageMapper.toMessageData(message)

        assertEquals(Role.ASSISTANT, messageData.role)
        assertEquals("I can help with that!", messageData.content)
    }

    @Test
    fun `toMessageData converts SystemMessage correctly`() {
        val message = SystemMessage(content = "System initialized")

        val messageData = MessageMapper.toMessageData(message)

        assertEquals(Role.SYSTEM, messageData.role)
        assertEquals("System initialized", messageData.content)
    }

    @Test
    fun `toMessage converts MessageData to UserMessage`() {
        val messageData = MessageData(
            messageId = "msg-123",
            role = Role.USER,
            content = "Hello!",
            createdAt = Instant.now()
        )

        val message = MessageMapper.toMessage(messageData, "Bob")

        assertTrue(message is UserMessage)
        assertEquals("Hello!", message.content)
        assertEquals("Bob", message.name)
    }

    @Test
    fun `toMessage converts MessageData to AssistantMessage`() {
        val messageData = MessageData(
            messageId = "msg-123",
            role = Role.ASSISTANT,
            content = "How can I help?",
            createdAt = Instant.now()
        )

        val message = MessageMapper.toMessage(messageData)

        assertTrue(message is AssistantMessage)
        assertEquals("How can I help?", message.content)
    }

    @Test
    fun `toMessage converts MessageData to SystemMessage`() {
        val messageData = MessageData(
            messageId = "msg-123",
            role = Role.SYSTEM,
            content = "Welcome to the chat",
            createdAt = Instant.now()
        )

        val message = MessageMapper.toMessage(messageData)

        assertTrue(message is SystemMessage)
        assertEquals("Welcome to the chat", message.content)
    }

    @Test
    fun `toMessage converts StoredMessage with author`() {
        val messageData = MessageData(
            messageId = "msg-123",
            role = Role.USER,
            content = "Hello!",
            createdAt = Instant.now()
        )
        val storedMessage = StoredMessage(
            message = messageData,
            author = TestSessionUserForMapper("user-1", "Alice")
        )

        val message = MessageMapper.toMessage(storedMessage)

        assertTrue(message is UserMessage)
        assertEquals("Hello!", message.content)
        assertEquals("Alice", message.name)
    }

    @Test
    fun `extension function Message toMessageData works`() {
        val message = UserMessage("Test message")

        val messageData = message.toMessageData()

        assertEquals(Role.USER, messageData.role)
        assertEquals("Test message", messageData.content)
    }

    @Test
    fun `extension function StoredMessage toMessage works`() {
        val storedMessage = StoredMessage(
            message = MessageData(
                messageId = "msg-123",
                role = Role.ASSISTANT,
                content = "Response",
                createdAt = Instant.now()
            )
        )

        val message = storedMessage.toMessage()

        assertTrue(message is AssistantMessage)
        assertEquals("Response", message.content)
    }

    @Test
    fun `extension function MessageData toMessage works`() {
        val messageData = MessageData(
            messageId = "msg-123",
            role = Role.USER,
            content = "Question",
            createdAt = Instant.now()
        )

        val message = messageData.toMessage("Charlie")

        assertTrue(message is UserMessage)
        assertEquals("Question", message.content)
        assertEquals("Charlie", message.name)
    }
}

/**
 * Simple test implementation of SessionUser for mapper tests.
 */
private data class TestSessionUserForMapper(
    override val id: String,
    override val displayName: String
) : com.embabel.chat.store.model.SessionUser