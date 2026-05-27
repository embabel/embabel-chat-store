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
package com.embabel.chat.store.embedding

import com.embabel.chat.AssistantMessage
import com.embabel.chat.MessageRole
import com.embabel.chat.SystemMessage
import com.embabel.chat.UserMessage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class RoleFilteringMessageEmbedderTest {

    private val expected = EmbeddingResult(floatArrayOf(0.1f, 0.2f), "test-model")

    private fun delegateReturning(result: EmbeddingResult?): MessageEmbedder = mock<MessageEmbedder>().also {
        runBlocking { whenever(it.embed(any())).thenReturn(result) }
    }

    @Test
    fun `delegates for USER messages by default`() = runBlocking {
        val delegate = delegateReturning(expected)
        val filtered = RoleFilteringMessageEmbedder(delegate)

        assertEquals(expected, filtered.embed(UserMessage("hello")))
        verify(delegate).embed(any())
    }

    @Test
    fun `delegates for ASSISTANT messages by default`() = runBlocking {
        val delegate = delegateReturning(expected)
        val filtered = RoleFilteringMessageEmbedder(delegate)

        assertEquals(expected, filtered.embed(AssistantMessage("hi there")))
        verify(delegate).embed(any())
    }

    @Test
    fun `skips SYSTEM messages by default`() = runBlocking {
        val delegate = delegateReturning(expected)
        val filtered = RoleFilteringMessageEmbedder(delegate)

        assertNull(filtered.embed(SystemMessage("you are a helpful assistant")))
        verify(delegate, never()).embed(any())
    }

    @Test
    fun `skips blank-content messages even when role is permitted`() = runBlocking {
        val delegate = delegateReturning(expected)
        val filtered = RoleFilteringMessageEmbedder(delegate)

        assertNull(filtered.embed(UserMessage("   \n  ")))
        verify(delegate, never()).embed(any())
    }

    @Test
    fun `honours a custom role set`() = runBlocking {
        val delegate = delegateReturning(expected)
        val filtered = RoleFilteringMessageEmbedder(delegate, roles = setOf(MessageRole.SYSTEM))

        assertEquals(expected, filtered.embed(SystemMessage("system text")))
        assertNull(filtered.embed(UserMessage("user text")))
    }
}