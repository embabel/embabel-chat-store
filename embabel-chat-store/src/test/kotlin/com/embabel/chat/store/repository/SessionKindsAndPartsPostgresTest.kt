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

import com.embabel.chat.store.TestApplication
import org.drivine.manager.PersistenceManager
import org.drivine.test.SqlCypherFixtures
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * [SessionKindsAndPartsTests] on Postgres, which does not speak Cypher: fixtures and checks are worked
 * out over its graph.
 */
@SpringBootTest(classes = [TestApplication::class])
@ActiveProfiles("postgres")
class SessionKindsAndPartsPostgresTest : SessionKindsAndPartsTests() {
    override val fixtures: PersistenceManager
        get() = SqlCypherFixtures.over(persistenceManager)

    override fun prepare() = SqlCypherFixtures.clear(persistenceManager)
}
