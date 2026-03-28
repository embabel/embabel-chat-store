package com.embabel.chat.store.adapter

import com.embabel.chat.AssistantMessage
import com.embabel.chat.MessageRole
import com.embabel.chat.UserMessage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TitleGeneratorTest {

    // ==================== LlmTitleGenerator — single-arg constructor ====================

    @Test
    fun `LlmTitleGenerator single-arg constructor passes prompt to lambda without userId`() = runBlocking {
        var capturedPrompt: String? = null
        val generator = LlmTitleGenerator { prompt ->
            capturedPrompt = prompt
            "A Fine Title"
        }

        val messages = listOf(UserMessage(content = "Hello"))
        generator.generate(messages, userId = "user-1")

        assertNotNull(capturedPrompt)
        assertTrue(capturedPrompt!!.contains("Hello"))
    }

    // ==================== LlmTitleGenerator — two-arg constructor ====================

    @Test
    fun `LlmTitleGenerator two-arg constructor passes prompt and userId to lambda`() = runBlocking {
        var capturedPrompt: String? = null
        var capturedUserId: String? = null
        val generator = LlmTitleGenerator { prompt, userId ->
            capturedPrompt = prompt
            capturedUserId = userId
            "Topic Title"
        }

        val messages = listOf(UserMessage(content = "Tell me about Kotlin"))
        generator.generate(messages, userId = "alice")

        assertNotNull(capturedPrompt)
        assertTrue(capturedPrompt!!.contains("Tell me about Kotlin"))
        assertEquals("alice", capturedUserId)
    }

    @Test
    fun `LlmTitleGenerator two-arg constructor passes null userId when not provided`() = runBlocking {
        var capturedUserId: String? = "sentinel"
        val generator = LlmTitleGenerator { _, userId ->
            capturedUserId = userId
            "Some Title"
        }

        generator.generate(listOf(UserMessage(content = "Hi")))

        assertNull(capturedUserId)
    }

    // ==================== LlmTitleGenerator — fallback on exception ====================

    @Test
    fun `LlmTitleGenerator falls back to fallback string when lambda throws`() = runBlocking {
        val generator = LlmTitleGenerator(
            fallback = "My Fallback"
        ) { _: String ->
            throw RuntimeException("LLM unavailable")
        }

        val result = generator.generate(listOf(UserMessage(content = "Hello")))

        assertEquals("My Fallback", result)
    }

    // ==================== LlmTitleGenerator — fallback on blank result ====================

    @Test
    fun `LlmTitleGenerator falls back when lambda returns blank string`() = runBlocking {
        val generator = LlmTitleGenerator(
            fallback = "Blank Fallback"
        ) { _: String ->
            "   "
        }

        val result = generator.generate(listOf(UserMessage(content = "Hello")))

        assertEquals("Blank Fallback", result)
    }

    // ==================== LlmTitleGenerator — truncation ====================

    @Test
    fun `LlmTitleGenerator truncates result to maxLength`() = runBlocking {
        val generator = LlmTitleGenerator(
            maxLength = 10
        ) { _: String ->
            "A Very Long Title That Exceeds The Limit"
        }

        val result = generator.generate(listOf(UserMessage(content = "Hello")))

        assertEquals(10, result.length)
        assertEquals("A Very Lon", result)
    }

    // ==================== TruncatingTitleGenerator ====================

    @Test
    fun `TruncatingTitleGenerator truncates long first user message`() = runBlocking {
        val generator = TruncatingTitleGenerator(maxLength = 20)
        val messages = listOf(UserMessage(content = "This is a very long message that should definitely be truncated"))

        val result = generator.generate(messages)

        assertTrue(result.length <= 20)
        assertTrue(result.endsWith("..."))
    }

    @Test
    fun `TruncatingTitleGenerator passes through short messages unchanged`() = runBlocking {
        val generator = TruncatingTitleGenerator(maxLength = 50)
        val messages = listOf(UserMessage(content = "Short message"))

        val result = generator.generate(messages)

        assertEquals("Short message", result)
    }

    // ==================== FirstSentenceTitleGenerator ====================

    @Test
    fun `FirstSentenceTitleGenerator extracts first sentence`() = runBlocking {
        val generator = FirstSentenceTitleGenerator(maxLength = 100)
        val messages = listOf(
            UserMessage(content = "What is the weather today? I need to know for my trip.")
        )

        val result = generator.generate(messages)

        assertEquals("What is the weather today?", result)
    }
}