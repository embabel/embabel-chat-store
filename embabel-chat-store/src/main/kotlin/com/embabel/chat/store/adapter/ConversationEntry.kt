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

import com.embabel.chat.Message
import com.embabel.chat.store.model.DataAttachmentData
import com.embabel.chat.store.model.StoredUser

/**
 * A message of a [StoredConversation] with the ID it is stored under and, when its writer said
 * so, the ID of the message it answers. In a multi-party conversation, [author] and [recipients]
 * say who wrote it and who it was sent to, which the [Message] alone does not.
 */
data class ConversationEntry @JvmOverloads constructor(
    val messageId: String,
    val message: Message,
    val inReplyTo: String?,
    val author: StoredUser? = null,
    val recipients: List<StoredUser> = emptyList(),
    val dataAttachments: List<DataAttachmentData> = emptyList(),
)
