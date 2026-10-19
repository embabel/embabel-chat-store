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

import com.embabel.chat.store.model.AssetData
import com.embabel.chat.store.model.AttachmentData
import com.embabel.chat.store.model.DataAttachmentData
import com.embabel.chat.store.model.MessageData
import com.embabel.chat.store.model.StoredUser

/**
 * One message to add to a session, with who wrote it, who it is sent to, and what it carries.
 *
 * @param messageData the message node to write
 * @param author who wrote the message; null for one with no author, such as a system message
 * @param recipients everyone the message is sent to; a recipient named twice is stored once
 * @param attachments files attached to the message
 * @param assets durable asset metadata produced with an assistant message
 * @param dataAttachments structured data attached to the message
 * @see ChatSessionRepository.addMessage
 * @see ChatSessionRepository.createSessionWithMessage
 */
data class MessageWrite @JvmOverloads constructor(
    val messageData: MessageData,
    val author: StoredUser? = null,
    val recipients: List<StoredUser> = emptyList(),
    val attachments: List<AttachmentData> = emptyList(),
    val assets: List<AssetData> = emptyList(),
    val dataAttachments: List<DataAttachmentData> = emptyList(),
)
