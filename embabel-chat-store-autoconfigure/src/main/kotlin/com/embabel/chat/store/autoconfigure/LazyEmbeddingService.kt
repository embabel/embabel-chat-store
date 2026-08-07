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
package com.embabel.chat.store.autoconfigure

import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.PricingModel

/**
 * An [EmbeddingService] that resolves its delegate on first use rather than at
 * construction, and pins it thereafter.
 *
 * Deferring resolution keeps bean creation independent of embedding-model availability,
 * and of the order in which provider configurations register their services. A host whose
 * model arrives after startup picks up the real service on the first embedding call, and a
 * host with no model at all fails that call instead of failing the application context —
 * embedding failure is already non-fatal, see
 * [com.embabel.chat.store.adapter.StoredConversation].
 *
 * Pinning matters for provenance: a vector and the [name] recorded alongside it are read
 * separately, so a delegate that changed between the two reads would label a vector with
 * the wrong model. Resolution that throws is not pinned, so a service that only becomes
 * resolvable later is still picked up.
 */
class LazyEmbeddingService(
    private val resolve: () -> EmbeddingService,
) : EmbeddingService {

    @Volatile
    private var delegate: EmbeddingService? = null

    private fun delegate(): EmbeddingService = delegate ?: resolve().also { delegate = it }

    override val name: String get() = delegate().name

    override val provider: String get() = delegate().provider

    override val pricingModel: PricingModel? get() = delegate().pricingModel

    override val dimensions: Int get() = delegate().dimensions

    override fun embed(text: String): FloatArray = delegate().embed(text)

    override fun embed(texts: List<String>): List<FloatArray> = delegate().embed(texts)
}
