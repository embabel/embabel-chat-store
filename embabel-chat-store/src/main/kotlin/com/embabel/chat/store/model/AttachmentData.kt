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
 * A file attached to a message — an image, a document, a data export.
 *
 * The BYTES ARE NOT STORED HERE. [storageUri] references content held outside the graph;
 * a graph is the wrong place for blobs, and message reads would pay for them on every load.
 * This node carries only what a caller needs to list, identify, and fetch an attachment.
 *
 * Kept deliberately free of any notion of what the attachment is *for*: dispatch (does this
 * get OCR'd, ingested, imported?) is a host concern layered above persistence, so an
 * attachment whose intent is unresolved — or never resolved — is still durably attached to
 * the conversation it arrived in.
 */
@NodeFragment(labels = ["Attachment"])
data class AttachmentData(
    /**
     * Unique attachment identifier (UUIDv7, so attachments sort chronologically the same
     * way messages do).
     */
    @NodeId val attachmentId: String,

    /**
     * Original filename as supplied by the client, for display and for format sniffing
     * by extension. Never trusted as a path — [storageUri] is the only thing that locates
     * the content.
     */
    val filename: String,

    /**
     * IANA media type as supplied by the client, e.g. `image/png`, `application/pdf`,
     * `text/plain`. Advisory only: clients lie, and `text/plain` covers everything from a
     * note to a general ledger export. Treat it as a hint that narrows candidates, never
     * as proof of content.
     */
    val mimeType: String,

    /**
     * Size of the stored content in bytes.
     */
    val sizeBytes: Long,

    /**
     * Content hash (hex-encoded SHA-256) of the stored bytes. Lets the same file uploaded
     * twice be recognised as the same content, and gives a cheap integrity check on fetch.
     */
    val contentHash: String,

    /**
     * Reference to the stored bytes, outside the graph. Opaque to this module — resolving
     * it is the host's business, since where content lives (world directory, object store)
     * is a deployment concern.
     */
    val storageUri: String,

    /**
     * When the attachment was received.
     */
    val createdAt: Instant,
) {
    companion object {
        @JvmStatic
        @JvmOverloads
        fun of(
            filename: String,
            mimeType: String,
            sizeBytes: Long,
            contentHash: String,
            storageUri: String,
            createdAt: Instant = Instant.now(),
            attachmentId: String = UUIDv7.generateString(),
        ): AttachmentData = AttachmentData(
            attachmentId = attachmentId,
            filename = filename,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            contentHash = contentHash,
            storageUri = storageUri,
            createdAt = createdAt,
        )
    }
}
