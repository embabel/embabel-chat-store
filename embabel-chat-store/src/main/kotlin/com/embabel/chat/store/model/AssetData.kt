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

import com.embabel.chat.DurableAsset
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import java.time.Instant

/**
 * Persisted metadata for a [DurableAsset]. Content remains in the external
 * [storageUri] managed by the originating asset store.
 */
@NodeFragment(labels = ["StoredAsset"])
data class AssetData(
    @NodeId val storedAssetId: String,
    val assetId: String,
    val name: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val contentHash: String,
    val storageUri: String,
    val createdAt: Instant,
) {

    fun toAsset(): DurableAsset = DurableAsset(
        id = assetId,
        name = name,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        contentHash = contentHash,
        storageUri = storageUri,
        timestamp = createdAt,
    )

    companion object {

        @JvmStatic
        fun from(asset: DurableAsset, messageId: String): AssetData = AssetData(
            storedAssetId = "$messageId:${asset.id}",
            assetId = asset.id,
            name = asset.name,
            mimeType = asset.mimeType,
            sizeBytes = asset.sizeBytes,
            contentHash = asset.contentHash,
            storageUri = asset.storageUri,
            createdAt = asset.timestamp,
        )
    }
}
