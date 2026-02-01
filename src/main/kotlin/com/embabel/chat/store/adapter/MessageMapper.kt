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
import com.embabel.chat.Message
import com.embabel.chat.Role
import com.embabel.chat.SystemMessage
import com.embabel.chat.UserMessage
import com.embabel.chat.store.model.MessageData
import com.embabel.chat.store.model.StoredMessage
import java.util.UUID

/**
 * Maps between embabel-agent Message types and embabel-chat-store MessageData.
 *
 * This mapper handles conversion in both directions:
 * - [Message] -> [MessageData] for persistence
 * - [StoredMessage] -> [Message] for retrieval
 *
 * Note: Multimodal content (images) is not currently persisted - only text content
 * is stored. The metadata field can be used to store additional information if needed.
 *
 * ## Usage
 *
 * You can use either the object methods or the extension functions:
 * ```kotlin
 * // Object methods
 * val messageData = MessageMapper.toMessageData(message)
 * val message = MessageMapper.toMessage(storedMessage)
 *
 * // Extension functions (more ergonomic)
 * val messageData = message.toMessageData()
 * val message = storedMessage.toMessage()
 * ```
 */
object MessageMapper {

    /**
     * Convert an embabel-agent [Message] to [MessageData] for persistence.
     *
     * @param message the message to convert
     * @param messageId optional ID for the message (generated if not provided)
     * @return MessageData ready for persistence
     */
    @JvmStatic
    @JvmOverloads
    fun toMessageData(
        message: Message,
        messageId: String = UUID.randomUUID().toString()
    ): MessageData {
        return MessageData(
            messageId = messageId,
            role = message.role,
            content = message.content,
            createdAt = message.timestamp,
            metadata = buildMetadata(message)
        )
    }

    /**
     * Convert a [StoredMessage] back to an embabel-agent [Message].
     *
     * @param storedMessage the stored message to convert
     * @return the appropriate Message subtype based on role
     */
    @JvmStatic
    fun toMessage(storedMessage: StoredMessage): Message {
        return toMessage(storedMessage.message, storedMessage.author?.displayName)
    }

    /**
     * Convert [MessageData] back to an embabel-agent [Message].
     *
     * @param messageData the message data to convert
     * @param authorName optional author name for the message
     * @return the appropriate Message subtype based on role
     */
    @JvmStatic
    @JvmOverloads
    fun toMessage(messageData: MessageData, authorName: String? = null): Message {
        return when (messageData.role) {
            Role.USER -> UserMessage(
                content = messageData.content,
                name = authorName,
                timestamp = messageData.createdAt
            )
            Role.ASSISTANT -> AssistantMessage(
                content = messageData.content,
                name = authorName,
                timestamp = messageData.createdAt
            )
            Role.SYSTEM -> SystemMessage(
                content = messageData.content,
                timestamp = messageData.createdAt
            )
        }
    }

    /**
     * Build metadata map from message properties.
     * Can be extended to store additional information.
     */
    private fun buildMetadata(message: Message): Map<String, Any>? {
        val metadata = mutableMapOf<String, Any>()

        // Store original sender name if present
        message.name?.let { metadata["senderName"] = it }

        // Flag multimodal messages (content not fully preserved)
        if (message.isMultimodal) {
            metadata["isMultimodal"] = true
            metadata["imageCount"] = message.imageParts.size
        }

        return metadata.ifEmpty { null }
    }
}

/**
 * Extension function to convert a [Message] to [MessageData].
 */
fun Message.toMessageData(messageId: String = UUID.randomUUID().toString()): MessageData =
    MessageMapper.toMessageData(this, messageId)

/**
 * Extension function to convert a [StoredMessage] to a [Message].
 */
fun StoredMessage.toMessage(): Message =
    MessageMapper.toMessage(this)

/**
 * Extension function to convert [MessageData] to a [Message].
 */
fun MessageData.toMessage(authorName: String? = null): Message =
    MessageMapper.toMessage(this, authorName)
