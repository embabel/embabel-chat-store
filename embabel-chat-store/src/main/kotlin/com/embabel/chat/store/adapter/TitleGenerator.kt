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

import com.embabel.chat.StoredMessage

/**
 * Strategy for generating conversation titles from messages.
 *
 * Implementations can range from simple truncation to LLM-powered summarization.
 */
fun interface TitleGenerator {

    /**
     * Generate a title from the given message.
     *
     * @param message the message to generate a title from (typically the first user message)
     * @return a short title for the conversation
     */
    suspend fun generate(message: StoredMessage): String

    companion object {
        /**
         * Default prompt for LLM-based title generation.
         */
        const val DEFAULT_PROMPT = "Generate a short title (max 6 words) for this message. " +
            "Reply with ONLY the title, no quotes or punctuation: "

        /**
         * Default maximum title length.
         */
        const val DEFAULT_MAX_LENGTH = 50

        /**
         * Default fallback title when generation fails.
         */
        const val DEFAULT_FALLBACK = "New conversation"
    }
}

/**
 * Simple title generator that truncates the message content.
 *
 * Useful as a fast, non-LLM fallback.
 *
 * @param maxLength maximum title length (default 50)
 * @param ellipsis suffix to add when truncating (default "...")
 */
class TruncatingTitleGenerator(
    private val maxLength: Int = TitleGenerator.DEFAULT_MAX_LENGTH,
    private val ellipsis: String = "..."
) : TitleGenerator {

    override suspend fun generate(message: StoredMessage): String {
        val content = message.content.trim()
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")

        return if (content.length <= maxLength) {
            content
        } else {
            content.take(maxLength - ellipsis.length).trimEnd() + ellipsis
        }
    }
}

/**
 * Title generator that extracts the first sentence or phrase.
 *
 * @param maxLength maximum title length (default 50)
 */
class FirstSentenceTitleGenerator(
    private val maxLength: Int = TitleGenerator.DEFAULT_MAX_LENGTH
) : TitleGenerator {

    private val sentenceEnders = Regex("[.!?]")

    override suspend fun generate(message: StoredMessage): String {
        val content = message.content.trim()
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")

        // Find first sentence
        val firstSentence = sentenceEnders.find(content)?.let {
            content.substring(0, it.range.first + 1)
        } ?: content

        // Truncate if needed
        return if (firstSentence.length <= maxLength) {
            firstSentence
        } else {
            firstSentence.take(maxLength - 3).trimEnd() + "..."
        }
    }
}

/**
 * Title generator that uses an LLM to create a concise title.
 *
 * The LLM call is abstracted via a suspend function, allowing flexible integration
 * with different LLM providers (PromptRunner, Chatbot, direct API calls, etc.).
 *
 * ## Usage with PromptRunner
 *
 * ```kotlin
 * val titleGenerator = LlmTitleGenerator { prompt ->
 *     promptRunner.generate(prompt)
 * }
 * ```
 *
 * ## Usage with Chatbot
 *
 * ```kotlin
 * val titleGenerator = LlmTitleGenerator { prompt ->
 *     // Create one-shot session for title generation
 *     val session = chatbot.createSession(user, outputChannel, null)
 *     session.onUserMessage(UserMessage(prompt))
 *     capturedResponse
 * }
 * ```
 *
 * @param prompt the prompt template (default asks for 6-word title)
 * @param maxLength maximum title length (default 100)
 * @param fallback title to use if generation fails (default "New conversation")
 * @param llmCall suspend function that sends a prompt to the LLM and returns the response
 */
class LlmTitleGenerator(
    private val prompt: String = TitleGenerator.DEFAULT_PROMPT,
    private val maxLength: Int = 100,
    private val fallback: String = TitleGenerator.DEFAULT_FALLBACK,
    private val llmCall: suspend (String) -> String
) : TitleGenerator {

    override suspend fun generate(message: StoredMessage): String {
        return try {
            val fullPrompt = prompt + message.content
            llmCall(fullPrompt)
                .trim()
                .take(maxLength)
                .ifBlank { fallback }
        } catch (e: Exception) {
            fallback
        }
    }
}

/**
 * Composing title generator that tries the primary generator first,
 * falling back to a secondary generator on failure.
 *
 * Useful for combining LLM-based generation with a simple fallback.
 *
 * ```kotlin
 * val generator = FallbackTitleGenerator(
 *     primary = LlmTitleGenerator { ... },
 *     fallback = TruncatingTitleGenerator()
 * )
 * ```
 */
class FallbackTitleGenerator(
    private val primary: TitleGenerator,
    private val fallback: TitleGenerator
) : TitleGenerator {

    override suspend fun generate(message: StoredMessage): String {
        return try {
            primary.generate(message)
        } catch (e: Exception) {
            fallback.generate(message)
        }
    }
}