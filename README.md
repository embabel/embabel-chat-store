# Embabel Chat Store

Conversation persistence layer for Embabel Agent. Provides pluggable storage backends
for chat sessions — in-memory for development/testing, and a graph-database backing for
production. Backed by Drivine and supports **Neo4j 5.x**, **Memgraph 2.x**, and
**FalkorDB 4.x** out of the box; the matching dialect is detected automatically from
Drivine's connection type.

## Quick Start

```kotlin
// Get a factory for your storage type
val factory = conversationFactoryProvider.get(ConversationStoreType.STORED)

// Create a conversation for a 1-1 chat
val conversation = (factory as StoredConversationFactory)
    .createForParticipants(
        id = sessionId,
        user = currentUser,
        agent = assistantUser,
        title = "My Chat"
    )

// Add messages - automatically routed based on role
conversation.addMessage(UserMessage("Hello!"))      // from=user, to=agent
conversation.addMessage(AssistantMessage("Hi!"))    // from=agent, to=user
```

## Architecture

```
ConversationFactoryProvider
    ├── InMemoryConversationFactory  → InMemoryConversation  (ephemeral)
    └── StoredConversationFactory    → StoredConversation    (graph DB)
                                         │
                                         ├── MessageEmbedder?      (optional: vector per message)
                                         └── VectorIndexManager    (per-DB index DDL)
```

### Storage Types

| Type | Class | Use Case |
|------|-------|----------|
| `IN_MEMORY` | `InMemoryConversation` | Testing, ephemeral chats |
| `STORED` | `StoredConversation` | Production, persistent chats |

## Durable Assistant Assets

`StoredConversation` persists `DurableAsset` metadata carried by an `AssistantMessage` and
restores those assets when the conversation is loaded again:

```kotlin
val message = AssistantMessage(
    content = "Here is the report",
    assets = listOf(durableAsset),
)
conversation.addMessage(message)
```

Asset metadata is stored in `StoredAsset` nodes connected to the message through `HAS_ASSET`.
The content bytes are not stored in the graph. `storageUri` remains an opaque reference resolved
by the `AssetStore` that originally materialized the asset.

Only `DurableAsset` instances are persisted. Ephemeral or application-specific `Asset`
implementations remain available while the message is pending in memory but cannot be restored
after a reload. Applications should materialize temporary assets before adding the assistant
message to a stored conversation.

Deleting a session deletes its `StoredAsset` metadata nodes. It does not delete externally stored
content; retention and content deletion remain the responsibility of the corresponding `AssetStore`.

## Message Events

Subscribe to message lifecycle events for real-time updates:

```kotlin
@EventListener
fun onMessage(event: MessageEvent) {
    when (event.status) {
        MessageStatus.ADDED -> {
            // Message added - update UI immediately
            sendToWebSocket(event.toUserId, event)
        }
        MessageStatus.PERSISTED -> {
            // Message saved to storage
        }
        MessageStatus.PERSISTENCE_FAILED -> {
            // Handle error - event.error has details
        }
    }
}
```

### Event Fields

| Field | Description |
|-------|-------------|
| `conversationId` | Session ID |
| `fromUserId` | Who sent the message |
| `toUserId` | Who should receive it (for routing) |
| `title` | Session title (for UI display) |
| `message` | The message content |
| `status` | ADDED, PERSISTED, or PERSISTENCE_FAILED |

## Message Attribution

Messages have explicit sender and recipient:

```
(message:StoredMessage)-[:AUTHORED_BY]->(from:User)
(message:StoredMessage)-[:SENT_TO]->(to:User)
```

### Role-Based Routing (1-1 Chats)

| Role | From | To |
|------|------|-----|
| USER | user | agent |
| ASSISTANT | agent | user |
| SYSTEM | null | user |

### Multi-Party Chats

For multi-user or multi-agent scenarios, use `addMessageFromTo` for explicit routing:

```kotlin
// Create conversation without default participants
val conversation = factory.create(sessionId)

// Group chat with multiple users
conversation.addMessageFromTo(UserMessage("Hi everyone!"), from = alice, to = bob)
conversation.addMessageFromTo(UserMessage("Hello Alice!"), from = bob, to = alice)
conversation.addMessageFromTo(UserMessage("Hey both!"), from = charlie, to = alice)

// Agent handoff - one agent passing to another
conversation.addMessageFromTo(
    AssistantMessage("Transferring you to billing..."),
    from = supportAgent,
    to = customer
)
conversation.addMessageFromTo(
    AssistantMessage("Hi, I'm the billing specialist."),
    from = billingAgent,
    to = customer
)

// Multi-agent collaboration
conversation.addMessageFromTo(
    AssistantMessage("I'll research that."),
    from = researchAgent,
    to = coordinatorAgent
)
conversation.addMessageFromTo(
    AssistantMessage("Here's what I found..."),
    from = researchAgent,
    to = customer
)
```

#### Future Enhancements

Planned support for:
- Group recipients (send to multiple users at once)
- Participant lists on conversations
- Agent-to-agent communication patterns
- Broadcast messages

## Sorting and Pagination

### Two orderings

Session listings support two orderings, selected with `SessionOrder`:

| `SessionOrder`  | Keyset                          | Meaning                        |
|-----------------|---------------------------------|--------------------------------|
| `CREATED`       | `sessionId`                     | Newest-created first (default) |
| `LAST_ACTIVITY` | `(lastActivityAt, sessionId)`   | Most recently active first     |

`CREATED` keys on the session ID alone. Session IDs are UUIDv7, which embeds a millisecond
timestamp in the leading 48 bits, and the canonical string form is big-endian hex — so
lexicographic comparison *is* chronological comparison. The ID is unique, so this is already
a total order and needs no tie-breaker, and it is served by the index backing the `sessionId`
uniqueness constraint. Two sessions created in the same millisecond order by the random bits
rather than by true creation time: arbitrary, but stable.

`LAST_ACTIVITY` orders by `lastActivityAt` descending, with `sessionId` descending purely as
a tie-breaker — the ID is not the primary sort key here.

Message order *within* a thread is always by `messageId` and is not configurable. Since
message IDs are also UUIDv7, a thread always reads in the order it happened.

### What counts as activity

`lastActivityAt` advances **only when a message is added**. It is deliberately not touched by:

- narration (`updateMessageNarration`)
- title generation or renaming
- any other enrichment of an existing message

Editing or annotating a message should not jump its session to the top of a list, any more
than it should move the message within its thread. The timestamp is also monotonic — it only
ever moves forward — so clock skew between application instances can leave ordering slightly
stale but can never move a session backwards, which would let a keyset walk skip or repeat it.

### Paging

Paging is keyset-based, not offset-based, so page *n* costs the same as page 1:

```kotlin
var cursor: String? = null
do {
    val page = repository.listSessionsForUser(
        userId,
        SessionPageRequest(pageSize = 20, cursor = cursor, order = SessionOrder.CREATED),
    )
    page.items.forEach { render(it) }
    cursor = page.nextCursor
} while (cursor != null)
```

`nextCursor` is null on the last page. Use `listSessionSummariesForUser` for the same paging
over lightweight summaries — owner plus a message count computed in the query, without
loading message bodies.

Cursors are **opaque**: a versioned, Base64URL-encoded binary payload. Do not parse or
construct them. Each cursor also records the `SessionOrder` that issued it, and is rejected
if replayed against the other ordering — the two key on different properties, so silently
seeking on the wrong one would return a plausible but wrong page.

There is no snapshot isolation across requests. Under `LAST_ACTIVITY`, a session that becomes
active mid-walk can move ahead of a cursor already issued and so be seen twice or missed.
`CREATED` keys on an immutable value and does not have this property.

### Upgrading from before `lastActivityAt`

`SessionData.lastActivityAt` carries Drivine's `@Default`, so a session stored before the
property existed hydrates as its `createdAt` instead of failing to load. Old data therefore
stays readable with no migration, and sorts sensibly under `CREATED` — which is another reason
that is the default ordering.

`LAST_ACTIVITY` needs more: a keyset comparison is never satisfied by a value that is absent in
the graph, so a session whose property is only defaulted client-side would still drop out of
every page after the first. `SessionActivityMigration` materialises it, and the
auto-configuration runs it on startup. It works in bounded batches (the un-backfilled set
cannot use the range index, since Neo4j does not index nulls), coalesces to an epoch floor so
sessions with neither messages nor a `createdAt` still converge, and records a marker node so
later boots cost a single lookup rather than a full label scan. Any session that receives a
message also repairs itself, since the write advances `lastActivityAt` when it is missing.

So the backfill is a correctness step for one ordering, not a prerequisite for reading. If no
`PersistenceManager` bean is present it cannot run, and the auto-configuration logs a warning
saying so; `CREATED` is unaffected either way.

## Auto Title Generation

Provide a `TitleGenerator` to automatically generate session titles:

```kotlin
val factory = StoredConversationFactory(
    repository = chatSessionRepository,
    eventPublisher = applicationEventPublisher,
    titleGenerator = { message ->
        // Generate title from first message
        llm.generate("Summarize in 5 words: ${message.content}")
    }
)
```

## Vector Embeddings on Messages

When a `MessageEmbedder` is wired, every persisted message is embedded inline within
the existing async-persistence coroutine — the embedding lands with the message in a
single DB write, no extra round-trip per message.

```
addMessage()
  ├─ MessageEvent(ADDED)              ← synchronous (front-ends update here)
  └─ async {
       embedder.embed(message)        ← inline, before the DB write
       repository.addMessage(... embedding ...)
       MessageEvent(PERSISTED)
     }
```

The embedding is stored as two properties on the `:StoredMessage` node:

```cypher
(msg:StoredMessage {
  messageId, role, content, createdAt,
  embedding,                          // List<Float> — works on Neo4j/Memgraph/FalkorDB
  embeddingModel                      // EmbeddingService.name, for drift detection
})
```

`embedding` and `embeddingModel` are both nullable. Messages without an embedding
(failure, no embedder configured, SYSTEM/blank content) just have null values.

### Embedder Configuration

The auto-configuration wires a default embedder when an embabel `Ai` bean is available:

```kotlin
RoleFilteringMessageEmbedder(           // Skips SYSTEM and blank-content messages
    DefaultMessageEmbedder(             // Calls ai.withDefaultEmbeddingService()
        ai.withDefaultEmbeddingService()
    )
)
```

To override — embed all roles, use a different embedding service, etc. — define your
own `MessageEmbedder` bean and the autoconfig backs off.

### Failure Mode

Embedding failures are caught and logged; the message is persisted with a null
embedding. Messages are never lost because an embedding call failed.

## Vector Index Management

The chat-store creates and manages a vector index on `:StoredMessage(embedding)`
automatically at application startup. The dialect (Neo4j / Memgraph / FalkorDB) is
detected from Drivine's `PersistenceManager.type`.

```yaml
embabel:
  chat:
    store:
      vector-index:
        enabled: true                   # default
        label: StoredMessage            # default
        property: embedding             # default
        similarity-function: cosine     # cosine | euclidean
        name: null                      # default: ${label}_${property}_vector
```

On startup the autoconfig calls `VectorIndexManager.ensureIndex(...)` with the
dimensions taken from the configured `EmbeddingService.dimensions`. The call is
idempotent — three outcomes:

| Outcome | Meaning |
|---|---|
| `Created` | No prior index existed; one was created. Logged at INFO. |
| `AlreadyMatching` | An index with the requested shape was already present. Logged at DEBUG. |
| `Drift` | An index exists with a **different** shape (e.g. different dimensions). **Not auto-dropped.** Logged at WARN. |

### Handling Drift

When you change the embedding model — say from `text-embedding-3-small` (1536 dims) to
`text-embedding-3-large` (3072 dims) — `ensureIndex` will detect that the existing
index no longer matches and emit a warning. To resolve:

```kotlin
@Autowired lateinit var indexManager: VectorIndexManager

// Destructive — drops the old index, creates one for the new model.
// Existing :StoredMessage.embedding values are now stale and must be re-embedded.
indexManager.recreateIndex(
    VectorIndexConfig(
        label = "StoredMessage",
        property = "embedding",
        dimensions = newDimensions,
    )
)
```

Re-embedding existing messages is the caller's responsibility — the manager only
handles the index itself.

### Supported Databases

| Database | Index DDL | Has index name? | `IF NOT EXISTS` |
|---|---|---|---|
| Neo4j 5.13+ | `CREATE VECTOR INDEX … OPTIONS { indexConfig: { … } }` | Yes | Yes |
| Memgraph 2.x | `CREATE VECTOR INDEX … WITH CONFIG { dimension, metric, capacity }` | Yes | No — guarded via `vector_search.show_index_info()` |
| FalkorDB 4.x | `CREATE VECTOR INDEX FOR (n:L) ON (n.p) OPTIONS { … }` | No (label+property identifies) | No — guarded via `db.indexes()` |

Override the auto-selected manager by defining your own `VectorIndexManager` bean.

### Test Coverage of the Index Managers

| Database | Test coverage |
|---|---|
| Neo4j | Real Drivine testcontainer integration tests cover create / dedupe / drift detect / recreate / drop / named index / euclidean similarity. |
| Memgraph | Cypher-capture unit tests only — they verify the emitted `WITH CONFIG`, metric mapping, and introspection-row parsing, but do not run against a real Memgraph. |
| FalkorDB | Cypher-capture unit tests only — they verify the emitted `OPTIONS`, the bare-identifier keys, and `db.indexes()` row parsing, but do not run against a real FalkorDB. **The exact shape of the `options` map returned by `db.indexes()` for vector indexes is not fully documented upstream**; verify against a live FalkorDB the first time drift detection is exercised on it. The failure mode is fail-closed (`findIndex` returns null), which on `ensureIndex` produces an explicit FalkorDB-side error on the duplicate CREATE rather than a silent mismatch. |

Adding real-backend integration tests for Memgraph and FalkorDB is tracked as
a follow-up — `drivine4j` has examples for both.

## User Implementation

Implement `StoredUser` for your user type. The `StoredUser` interface extends `User` from
`embabel-agent-api` with Drivine annotations for graph-database persistence (the same
annotations work for Neo4j, Memgraph, and FalkorDB):

```kotlin
@NodeFragment(labels = ["User", "MyUser"])
data class MyUser(
    @NodeId override val id: String,
    override val displayName: String,
    override val username: String,
    override val email: String?
) : StoredUser
```

Register with Drivine for polymorphic loading:

```kotlin
persistenceManager.registerSubtype(
    StoredUser::class.java,
    "MyUser|User",  // Labels sorted alphabetically
    MyUser::class.java
)
```

For simple cases without extra fields, use the built-in `SimpleStoredUser`:

```kotlin
val user = SimpleStoredUser(
    id = "user-123",
    displayName = "Alice",
    username = "alice",
    email = "alice@example.com"
)
```

## Spring Boot Auto-Configuration

Add the dependency and configure:

```yaml
embabel:
  chat:
    store:
      enabled: true                  # default
      title-after-message-count: 1   # default: regenerate title every N messages
      vector-index:
        enabled: true                # default: ensure a vector index at startup
        similarity-function: cosine  # default
```

Beans auto-configured:

| Bean | Conditional on | Purpose |
|---|---|---|
| `storedConversationFactory` | `ChatSessionRepository` | Creates persistent conversations |
| `inMemoryConversationFactory` | — | Creates ephemeral conversations |
| `conversationFactoryProvider` | — | Aggregates all factories |
| `titleGenerator` | `Ai` bean | LLM-driven title generation |
| `messageEmbedder` | `Ai` bean | Inline embedding on persisted messages |
| `vectorIndexManager` | `PersistenceManager` | Per-DB vector-index DDL (auto-selected from `DatabaseType`) |
| `vectorIndexEnsurer` | `VectorIndexManager` + `Ai` + property | Runs `ensureIndex` on app startup |

Each of these can be overridden by defining your own bean of the same type.

## Dependencies

- `embabel-agent-api` - Core conversation interfaces
- `drivine` - Neo4j graph persistence
- Spring Boot (optional, for auto-configuration)
