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
package com.embabel.chat.support

import com.embabel.agent.api.identity.User
import com.embabel.chat.AssetTracker
import com.embabel.chat.Conversation
import com.embabel.chat.Message
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Simple in-memory implementation of [Conversation].
 *
 * Messages are stored in memory and not persisted. Suitable for testing,
 * ephemeral sessions, or as a delegate for decorators like [EventPublishingConversation].
 *
 * Thread-safe: uses [CopyOnWriteArrayList] for message storage.
 *
 * @param id unique identifier for this conversation
 * @param initialMessages optional list of messages to start with
 * @param assetTracker the asset tracker for this conversation
 */
class InMemoryStorableConversation(
    override val id: String,
    initialMessages: List<Message> = emptyList(),
    override val assetTracker: AssetTracker = InMemoryAssetTracker()
) : Conversation {

    private val _messages = CopyOnWriteArrayList(initialMessages)

    override val messages: List<Message>
        get() = _messages.toList()

    override fun persistent(): Boolean = false

    override fun addMessage(message: Message): Message {
        _messages.add(message)
        return message
    }

    override fun addMessageFrom(message: Message, author: User?): Message {
        return addMessage(message)
    }

    override fun addMessageFromTo(
        message: Message,
        from: User?,
        to: User?
    ): Message {
        return addMessage(message)
    }

    override fun last(n: Int): Conversation {
        return InMemoryStorableConversation(
            id = id,
            initialMessages = _messages.takeLast(n),
            assetTracker = assetTracker
        )
    }
}
