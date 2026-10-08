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

import com.embabel.chat.UserMessage
import com.embabel.chat.event.MessageEvent
import com.embabel.chat.event.MessageStatus
import com.embabel.chat.store.TestApplication
import com.embabel.chat.store.event.SessionEventAwaiter
import com.embabel.chat.store.model.TestSessionUser
import com.embabel.chat.store.repository.ChatSessionRepository
import org.drivine.manager.GraphObjectManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID

@SpringBootTest(classes = [TestApplication::class])
class StoredConversationFactoryIntegrationTest {

    @Autowired
    private lateinit var repository: ChatSessionRepository

    @Autowired
    private lateinit var graphObjectManager: GraphObjectManager

    @Test
    fun `first message persists without explicitly creating a session`() {
        val sessionId = UUID.randomUUID().toString()
        val owner = TestSessionUser(UUID.randomUUID().toString(), "Owner")
        val publisher: ApplicationEventPublisher = mock()
        graphObjectManager.save(owner)

        try {
            val conversation = StoredConversationFactory(repository, SessionEventAwaiter(), publisher)
                .createForParticipants(sessionId, owner, null, "Test session")

            assertTrue(repository.findBySessionId(sessionId).isPresent)
            conversation.addMessage(UserMessage("Hello"))

            val events = ArgumentCaptor.forClass(Any::class.java)
            verify(publisher, timeout(30000).atLeast(2)).publishEvent(events.capture())
            assertTrue(events.allValues.filterIsInstance<MessageEvent>()
                .any { it.status == MessageStatus.PERSISTED })
            assertEquals("Hello", repository.getMessages(sessionId).single().content)
        } finally {
            repository.deleteSession(sessionId)
        }
    }
}
