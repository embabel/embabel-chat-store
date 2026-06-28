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
package com.embabel.chat.store.util

import com.fasterxml.uuid.Generators
import java.util.UUID

/**
 * Utility for generating UUIDv7 (time-ordered) identifiers.
 *
 * UUIDv7 embeds a Unix timestamp in the first 48 bits, making them:
 * - Naturally sortable by creation time (lexicographic order = chronological order)
 * - Efficient for database indexing
 * - Globally unique without coordination
 */
object UUIDv7 {

    private val generator = Generators.timeBasedEpochGenerator()

    /**
     * Generate a new UUIDv7.
     */
    fun generate(): UUID = generator.generate()

    /**
     * Generate a new UUIDv7 as a string.
     */
    fun generateString(): String = generate().toString()
}
