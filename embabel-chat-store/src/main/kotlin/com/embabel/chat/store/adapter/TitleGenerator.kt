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

/**
 * Strategy for generating conversation titles from messages.
 *
 * Implementations can range from simple truncation to LLM-powered summarization.
 */
interface TitleGenerator {

    /**
     * Generate a title from the conversation messages.
     *
     * @param messages the messages to generate a title from
     * @param currentTitle the existing title, if any — implementations may keep it if still relevant
     * @return a short title for the conversation
     */
    suspend fun generate(messages: List<Message>, currentTitle: String? = null): String

    companion object {
        /**
         * Default prompt for LLM-based title generation.
         */
        const val DEFAULT_PROMPT = "Generate a short title (max 6 words) for this conversation. " +
            "Reply with ONLY the title, no quotes or punctuation:\n\n"

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
 * Simple title generator that truncates the first user message.
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

    override suspend fun generate(messages: List<Message>, currentTitle: String?): String {
        val firstUserMessage = messages.firstOrNull { it.role == com.embabel.chat.MessageRole.USER }
            ?: messages.firstOrNull()
            ?: return TitleGenerator.DEFAULT_FALLBACK

        val content = firstUserMessage.content.trim()
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
 * Title generator that extracts the first sentence from the first user message.
 *
 * @param maxLength maximum title length (default 50)
 */
class FirstSentenceTitleGenerator(
    private val maxLength: Int = TitleGenerator.DEFAULT_MAX_LENGTH
) : TitleGenerator {

    private val sentenceEnders = Regex("[.!?]")

    override suspend fun generate(messages: List<Message>, currentTitle: String?): String {
        val firstUserMessage = messages.firstOrNull { it.role == com.embabel.chat.MessageRole.USER }
            ?: messages.firstOrNull()
            ?: return TitleGenerator.DEFAULT_FALLBACK

        val content = firstUserMessage.content.trim()
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
 * Title generator that uses an LLM to create a concise title from the conversation.
 *
 * When multiple messages are available, formats them as a conversation transcript
 * so the LLM can generate a title that captures the topic, not just the greeting.
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

    override suspend fun generate(messages: List<Message>, currentTitle: String?): String {
        return try {
            val transcript = messages.joinToString("\n") { msg ->
                val content = if (msg.content.length > 200) msg.content.take(200) + "..." else msg.content
                "${msg.role.name.lowercase()}: $content"
            }
            val titleContext = if (currentTitle != null) {
                "The current title is: \"$currentTitle\"\n" +
                    "If this title still accurately describes the conversation, respond with exactly the same title.\n" +
                    "Otherwise, generate a better one.\n\n"
            } else {
                ""
            }
            val fullPrompt = prompt + titleContext + transcript
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

    override suspend fun generate(messages: List<Message>, currentTitle: String?): String {
        return try {
            primary.generate(messages, currentTitle)
        } catch (e: Exception) {
            fallback.generate(messages, currentTitle)
        }
    }
}