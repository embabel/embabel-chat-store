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

/**
 * A re-embed of stored messages that finished without rewriting every message it should have.
 *
 * Thrown by [ChatSessionRepository.reembedMessages] AFTER the message vector index has been put
 * back, so catching it never leaves message search without an index. Everything that did embed
 * was written and is not repeated: a rerun asks only for what is still not at the current model,
 * which is exactly [failedMessageIds] plus the [notAttempted] remainder.
 *
 * A failing `embed` call is split and retried on halves, down to single messages, so one message
 * the service refuses (too long for its context, say) costs only itself. When calls keep failing
 * back to back the service is treated as down and the run stops, rather than spending roughly two
 * calls per remaining message discovering that; see [abandoned].
 *
 * @param failedMessageIds messages the service refused on their own; retrying them unchanged
 * against the same model will likely fail again
 * @param notAttempted pending messages never sent because the run was abandoned; zero when
 * [abandoned] is false
 * @param report what the run did manage: how many messages it rewrote, and whether the index was
 * remade
 * @param abandoned whether the run stopped early because consecutive embed calls kept failing
 * @param cause the last embedding failure seen
 */
class MessageReembedIncompleteException(
    val failedMessageIds: List<String>,
    val notAttempted: Int,
    val report: MessageReembedReport,
    val abandoned: Boolean,
    cause: Throwable?,
) : RuntimeException(describe(failedMessageIds, notAttempted, report, abandoned), cause) {

    private companion object {
        const val MAX_IDS_IN_MESSAGE = 20

        fun describe(failed: List<String>, notAttempted: Int, report: MessageReembedReport, abandoned: Boolean): String {
            val abandonedPart = if (abandoned) ", abandoned with $notAttempted not attempted" else ""
            val more = if (failed.size > MAX_IDS_IN_MESSAGE) " and ${failed.size - MAX_IDS_IN_MESSAGE} more" else ""
            return """Re-embed incomplete: ${report.messages} messages re-embedded, ${failed.size} failed$abandonedPart; failed ids: ${failed.take(MAX_IDS_IN_MESSAGE)}$more"""
        }
    }
}
