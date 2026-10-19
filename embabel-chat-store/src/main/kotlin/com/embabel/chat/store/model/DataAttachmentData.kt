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
package com.embabel.chat.store.model

import com.embabel.chat.store.util.UUIDv7
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import java.time.Instant

/**
 * Structured data attached to a message: a record, a query result, anything the host can write
 * as JSON and wants the reader of the message to receive as data rather than as prose.
 *
 * The counterpart of [AttachmentData], which references a file stored outside the graph. A data
 * attachment is small and is the content itself, so it is stored here, with the message.
 *
 * Opaque to this module: [type] and [json] are the host's to define and read.
 *
 * A data attachment belongs to one message and is deleted with it. Give each message its own
 * ([of] makes a new ID each time): the same [dataAttachmentId] on two messages is one node, and
 * deleting either message's session deletes it from both.
 */
@NodeFragment(labels = ["DataAttachment"])
data class DataAttachmentData(
    /** Unique identifier (UUIDv7, so data attachments sort in the order they were made). */
    @NodeId val dataAttachmentId: String,

    /** What kind of data this is, in the host's terms, e.g. `entity` or `view`. */
    val type: String,

    /** What a person reads where the attachment is shown. */
    val title: String,

    /** The data, as JSON. */
    val json: String,

    /** When the data was attached. */
    val createdAt: Instant,
) {
    companion object {
        @JvmStatic
        @JvmOverloads
        fun of(
            type: String,
            title: String,
            json: String,
            createdAt: Instant = Instant.now(),
            dataAttachmentId: String = UUIDv7.generateString(),
        ): DataAttachmentData = DataAttachmentData(
            dataAttachmentId = dataAttachmentId,
            type = type,
            title = title,
            json = json,
            createdAt = createdAt,
        )
    }
}
