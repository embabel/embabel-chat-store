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

import com.embabel.chat.TextPart
import com.embabel.chat.store.model.AssetData
import com.embabel.chat.store.model.ContentPartData
import com.embabel.chat.store.model.MessageData
import com.embabel.chat.store.model.StoredSession
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doCallRealMethod
import org.mockito.Mockito.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class ChatSessionRepositoryTest {

    @Test
    fun `default content part persistence falls back without rejecting the message`() {
        val repository = mock<ChatSessionRepository>()
        val message = MessageData.from(com.embabel.chat.UserMessage("Keep this text"), "message-1")
        val assets = emptyList<AssetData>()
        val contentParts = listOf(ContentPartData.from(TextPart("Keep this text"), "message-1", 0))
        val storedSession = mock<StoredSession>()

        doCallRealMethod().`when`(repository).addMessageWithContentParts(
            "session-1", message, null, null, emptyList(), assets, contentParts,
        )
        doReturn(storedSession).`when`(repository).addMessageWithAssets(
            "session-1", message, null, null, emptyList(), assets,
        )

        val result = repository.addMessageWithContentParts(
            "session-1", message, null, null, emptyList(), assets, contentParts,
        )

        assertSame(storedSession, result)
        verify(repository).addMessageWithAssets(
            "session-1", message, null, null, emptyList(), assets,
        )
    }
}
