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

import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.EmbeddingService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DefaultMessageEmbedderTest {

    @Test
    fun `delegates to embedding service and stores its name on the result`() = runBlocking {
        val vector = floatArrayOf(0.1f, 0.2f, 0.3f)
        val service = mock<EmbeddingService>()
        whenever(service.name).thenReturn("text-embedding-3-small")
        whenever(service.embed(eq("hello"))).thenReturn(vector)

        val result = DefaultMessageEmbedder(service).embed(UserMessage("hello"))

        assertArrayEquals(vector, result?.vector)
        assertEquals("text-embedding-3-small", result?.model)
    }

    @Test
    fun `skips blank content without calling the embedding service`() = runBlocking {
        val service = mock<EmbeddingService>()

        val result = DefaultMessageEmbedder(service).embed(UserMessage("   \n  "))

        assertNull(result)
        verify(service, never()).embed(any<String>())
    }
}