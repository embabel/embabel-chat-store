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

import com.embabel.chat.ContentPart
import com.embabel.chat.DocumentPart
import com.embabel.chat.ImagePart
import com.embabel.chat.TextPart
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import java.util.Base64

/**
 * One ordered part of a multimodal user message.
 *
 * Binary content is stored inline because agent-api [ContentPart] values contain bytes rather
 * than a durable external reference. Callers that already externalize uploads should continue
 * to use [AttachmentData] instead.
 */
@NodeFragment(labels = ["StoredContentPart"])
data class ContentPartData(
    @NodeId val storedContentPartId: String,
    val position: Int,
    val type: ContentPartType,
    val text: String? = null,
    val mimeType: String? = null,
    val dataBase64: String? = null,
    val filename: String? = null,
) {

    fun toContentPart(): ContentPart = when (type) {
        ContentPartType.TEXT -> TextPart(requireNotNull(text))
        ContentPartType.IMAGE -> ImagePart(requireNotNull(mimeType), decodeData())
        ContentPartType.DOCUMENT -> DocumentPart(requireNotNull(mimeType), decodeData(), filename)
    }

    companion object {

        @JvmStatic
        fun from(part: ContentPart, messageId: String, position: Int): ContentPartData = when (part) {
            is TextPart -> ContentPartData(
                storedContentPartId = "$messageId:$position",
                position = position,
                type = ContentPartType.TEXT,
                text = part.text,
            )

            is ImagePart -> ContentPartData(
                storedContentPartId = "$messageId:$position",
                position = position,
                type = ContentPartType.IMAGE,
                mimeType = part.mimeType,
                dataBase64 = Base64.getEncoder().encodeToString(part.data),
            )

            is DocumentPart -> ContentPartData(
                storedContentPartId = "$messageId:$position",
                position = position,
                type = ContentPartType.DOCUMENT,
                mimeType = part.mimeType,
                dataBase64 = Base64.getEncoder().encodeToString(part.data),
                filename = part.filename,
            )
        }
    }

    private fun decodeData(): ByteArray = Base64.getDecoder().decode(requireNotNull(dataBase64))
}

enum class ContentPartType {
    TEXT,
    IMAGE,
    DOCUMENT,
}
