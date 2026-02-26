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

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for Embabel Chat Store.
 */
@ConfigurationProperties(prefix = "embabel.chat.store")
class ChatStoreProperties {
    /**
     * Whether chat store auto-configuration is enabled.
     * Default: true
     */
    var enabled: Boolean = true

    /**
     * Number of messages in a conversation before a title is generated.
     * Set to 1 for immediate title generation (legacy behavior).
     * Higher values (e.g., 6) wait for enough context to generate a meaningful title,
     * avoiding titles based on greetings like "Hey how are you?".
     * Default: 1
     */
    var titleAfterMessageCount: Int = 1
}