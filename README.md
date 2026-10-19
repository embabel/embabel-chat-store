<a href="https://hub.embabel.com"><img align="left" src="https://raw.githubusercontent.com/embabel/guide/main/logo.png" width="240"></a>

### **Embabel Chat Store**

![Build](https://github.com/embabel/embabel-chat-store/actions/workflows/maven.yml/badge.svg) [![Embabel](https://img.shields.io/badge/Embabel-Agent_Framework-6C3483?style=for-the-badge)](https://github.com/embabel/embabel-agent) ![Kotlin](https://img.shields.io/badge/kotlin-%237F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white) ![Spring](https://img.shields.io/badge/spring-%236DB33F.svg?style=for-the-badge&logo=spring&logoColor=white) ![Apache Maven](https://img.shields.io/badge/Apache%20Maven-C71A36?style=for-the-badge&logo=Apache%20Maven&logoColor=white)

<br clear="left"/>

Conversation persistence for the Embabel Agent framework: chat sessions, their messages, who sent
each one to whom, attachments, structured data and assets, keyset paging, and a vector per message. It runs on four
databases through [Drivine4j](https://github.com/liberation-data/drivine4j), and in memory for
development and tests.

## Supported databases

| Database | Notes |
|---|---|
| **Neo4j** | The default for this repository's tests. The message vector index needs 5.13 or later. |
| **FalkorDB** | Fastest on most workloads in [graph-bench](https://github.com/liberation-data/graph-bench). |
| **Memgraph** | Use the `memgraph/memgraph-mage` image, which has the vector search module. |
| **Oracle** ![New](https://img.shields.io/badge/NEW-ff3e00?style=flat-square) | Oracle Database 23ai or later. No Cypher: Drivine reads with SQL/PGQ and writes rows. Needs the JDBC driver. See [Oracle](#oracle). |

## See it running

[Guide](https://github.com/embabel/guide) is a working reference: an Embabel agent that answers
questions about Embabel from its documentation, and keeps its conversations in this store on any of
the four databases.
[Embabel Hub](https://hub.embabel.com) is its official front end.

<p align="center">
  <a href="https://www.youtube.com/watch?v=hY6ZFMIJdd4" target="_blank">
    <img src="https://raw.githubusercontent.com/embabel/guide/main/guide-demo.png" alt="The Voice, The Word, and The Wheel" width="700">
  </a>
</p>

## Usage

Add the starter:

```xml
<dependency>
    <groupId>com.embabel.agent</groupId>
    <artifactId>embabel-agent-starter-chat-store</artifactId>
    <version>0.7.0-SNAPSHOT</version>
</dependency>
```

Configure a Drivine datasource:

```yaml
database:
  datasources:
    neo:
      type: NEO4J          # or FALKORDB, MEMGRAPH; for ORACLE see below
      host: localhost
      port: 7687
      user-name: neo4j
      password: secret
      database-name: neo4j
```

Define the repository. The auto-configuration builds the conversation factories on it:

```kotlin
@Bean
fun chatSessionRepository(
    factory: GraphObjectManagerFactory,
    persistenceManager: PersistenceManager,
    eventPublisher: ApplicationEventPublisher,
): ChatSessionRepository =
    ChatSessionRepositoryImpl(factory.stateless(), persistenceManager, eventPublisher)
```

Then create a conversation and add messages:

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

The database is whichever one the datasource names. Nothing database-specific is passed in, so moving
from one to another is a configuration change.

### Oracle

Oracle Database 23ai or later. Oracle does not speak Cypher: Drivine reads it with
SQL/PGQ and writes rows, and the repository is the same class as on the other databases.

1. Add the JDBC driver. Drivine does not bring it:

   ```xml
   <dependency>
       <groupId>com.oracle.database.jdbc</groupId>
       <artifactId>ojdbc11</artifactId>
   </dependency>
   ```

2. Point the datasource at the database:

   ```yaml
   database:
     datasources:
       neo:
         type: ORACLE
         host: localhost
         port: 1521                  # the default
         database-name: FREEPDB1     # the service to connect to
         user-name: app
         password: secret
   ```

   Drivine makes the graph's tables and the property graph over them in this user's schema, the first
   time a manager speaks to the graph. The user needs the privileges to create them.

3. Define the repository as above.

## How it works

```
ConversationFactoryProvider
    ├── InMemoryConversationFactory  → InMemoryConversation  (ephemeral)
    └── StoredConversationFactory    → StoredConversation    (graph DB)
                                         │
                                         ├── ChatSessionRepository  (Drivine object manager)
                                         └── MessageEmbedder?       (optional: vector per message)
```

| Type | Class | Use Case |
|------|-------|----------|
| `IN_MEMORY` | `InMemoryConversation` | Testing, ephemeral chats |
| `STORED` | `StoredConversation` | Production, persistent chats |

A `StoredConversation` publishes each message as it is added, then persists it asynchronously
through **`ChatSessionRepository`**: sessions, messages, paging, titles, narration and re-embedding.

### Drivine

The model is a set of Drivine `@NodeFragment`s (`ChatSession`, `StoredMessage`, `User`,
`StoredAsset`, `DataAttachment`) and `@GraphView`s that load a node with what it is related to: a session
with its owner and messages, a message with its author, recipients, attachments, data attachments and
assets. `ChatSessionRepositoryImpl`
reads and writes them through Drivine's `StatelessGraphObjectManager` and the query DSL Drivine
generates at build time, so it holds no Cypher or SQL of its own. Drivine writes the statement for the
configured database: Cypher for Neo4j, FalkorDB and Memgraph, SQL/PGQ for Oracle.

The schema is declared, not written: the constraints, the `lastActivityAt` range index and the message
vector index are Drivine `SchemaCatalog`s, and Drivine's schema manager creates each in the database's
own form at startup. A load or a save is one statement and one round trip however many relationships it
carries.

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
| `toUserId` | Who should receive it (for routing). A message sent to several recipients names the first |
| `title` | Session title (for UI display) |
| `message` | The message content |
| `status` | ADDED, PERSISTED, or PERSISTENCE_FAILED |

`MessageEvent` has room for one recipient. Once a message is persisted, a `MessagePersistedEvent`
follows with its ID, its `authorId` and every one of its `recipientIds`, for listeners that route a
multi-party message to each recipient.

## Message Attribution

Messages have explicit sender and recipient:

```
(message:StoredMessage)-[:AUTHORED_BY]->(from:User)
(message:StoredMessage)-[:SENT_TO]->(to:User)          // one per recipient
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

#### Several recipients and structured data

`addAttributedMessage` names the author and every recipient of a message, and can attach structured
data as well as files. It returns the message's ID:

```kotlin
val conversation = factory.create(sessionId) as StoredConversation

val record = DataAttachmentData.of(
    type = "entity",                      // the host's own vocabulary
    title = "Customer Northwind",
    json = """{"id":"c-1","risk":0.8}""",
)
val questionId = conversation.addAttributedMessage(
    UserMessage("@Triage @Steward is this customer at risk?"),
    from = alice,
    recipients = listOf(triageAgent, stewardAgent),
    dataAttachments = listOf(record),
)
conversation.addAttributedMessage(
    AssistantMessage("Yes, three invoices are overdue."),
    from = triageAgent,
    recipients = listOf(alice),
    inReplyTo = questionId,
)

conversation.entries.forEach { entry ->
    println("${entry.author?.displayName} -> ${entry.recipients.map { it.displayName }}: ${entry.message.content}")
}
```

A data attachment is stored in the graph with its message (`HAS_DATA_ATTACHMENT`), and is deleted
with its session. Its `type` and `json` are opaque to the store. A message read back lists its
recipients in no particular order.

`ChatSessionRepository.addMessage(sessionId, MessageWrite(...))` and
`createSessionWithMessage(sessionId, owner, title, MessageWrite(...))` write the same at the
repository level. A custom `ChatSessionRepository` that implements neither still takes a message with
one recipient and no data attachment through them, and refuses anything more with
`IllegalArgumentException`.

#### Who took part

- `ChatSessionRepository.getParticipants(sessionId)` lists everyone who wrote in a session.
- `SessionSummary.startedBy` is the author of the earliest message that has one, read in the same
  query as the rest of the summary. A message with no author, such as a system greeting, is passed
  over; a session in which nobody has written yet has none.

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
over lightweight summaries — owner, a message count and who started the session, computed in the
query, without loading message bodies.

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
the graph, so a session whose property is only defaulted client-side drops out of every page
after the first. Every session written since 0.5.0 has the property, and an older one gains it
when it next receives a message.

Releases 0.5.0 and 0.6.0 backfilled the property at startup, for sessions stored before it
existed. That backfill ran once for a database and has been removed: a database that still
holds sessions from before 0.5.0 should be started once on 0.6.0 before it is upgraded further.

## Concurrent Writes

`SessionData` and `MessageData` each have a `stamp` field, annotated with Drivine's
`@NodeStamp`. Loading fills it. A save of a session or message that carries a stamp is applied
only if the node's properties are as they were when it was loaded, and is otherwise refused
with `StaleObjectException`. Nothing is written by a refused save.

```kotlin
val session = repository.findBySessionId(sessionId).get().session
// ... another writer adds a message, which advances lastActivityAt ...
graphObjectManager.save(session.copy(title = "New title"))   // StaleObjectException
```

The repository's own writes need no handling:

| Write | What it does about a concurrent change |
|---|---|
| `updateSessionTitle` | Writes only the title. If the session changed after it was loaded, it is loaded again and the title applied to the fresh one. |
| `addMessage` | Adds the message, and advances `lastActivityAt` on the session node alone, in one statement that compares with the stored value, so it only moves forward. |
| `updateMessageNarration` | Writes only the narration of the one message, in one statement, and only if the message is in the session named. |
| `reembedMessages` | Writes only the vector and its model, in one statement per message, so a message changed after its page was loaded keeps the change. |

Treat the stamp as opaque, and leave it null on a session or message you construct. It is part
of `equals`, `copy`, and any JSON made from these classes directly.

### Cypher of your own

A save notices only a write that replaced the stamp. Cypher that sets a property of a
`ChatSession` or `StoredMessage` node must therefore set `Stamps.setClause` in the same
statement:

```kotlin
"MATCH (m:StoredMessage {messageId: \$id}) SET m.narration = \$narration, ${Stamps.setClause("m")}"
```

Without it, a save of an object loaded before the statement ran overwrites what it wrote.

The repository itself writes no Cypher: each of its reads and writes goes through Drivine's
object manager, which marks the stamp and runs on every store Drivine supports.

### Upgrading from before stamps

`ChatSessionRepositoryImpl` takes a `StatelessGraphObjectManager`, where it took a
`GraphObjectManager`:

```kotlin
@Bean
fun chatSessionRepository(
    factory: GraphObjectManagerFactory,
    persistenceManager: PersistenceManager,
): ChatSessionRepository = ChatSessionRepositoryImpl(factory.stateless(), persistenceManager)
```

Stored data needs no migration. A session or message stored before stamps loads with a null
`stamp`, so its first save is not checked, and that save gives the node a stamp.

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
  embedding,                          // List<Float>
  embeddingModel                      // EmbeddingService.name, for drift detection
})
```

`embedding` and `embeddingModel` are both nullable. Messages without an embedding
(failure, no embedder configured, SYSTEM/blank content) just have null values.

When embedding a message fails, the message is still saved, with a null `embeddingModel`, a
WARN is logged with its id, and a `MessageEmbeddingFailedEvent` (session id, message id, role,
cause) is published once the write lands. A null model is what `reembedMessages` selects on, so
the retry is a re-embed run with the current model — `MessageReembedder.reembed(embeddingService)`
— triggered from a listener for that event or on a schedule. A run only embeds what is pending.

`reembedMessages` splits a failing embed call down to single messages, writes everything that
embeds, always remakes the message index, and then throws `MessageReembedIncompleteException`
naming the messages that failed (and, if it gave up on a dead service, how many it never tried).

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

The chat store declares a vector index on `:StoredMessage(embedding)` as a Drivine `SchemaCatalog`,
and Drivine's schema manager ensures it at startup, in the form the configured database takes.

```yaml
embabel:
  chat:
    store:
      vector-index:
        enabled: true                   # default
        label: StoredMessage            # default
        property: embedding             # default
        similarity-function: cosine     # cosine | euclidean
        name: null                      # default: Drivine derives one from label and property
```

The index is sized to the embedding model's dimensions and versioned by the model's name. With no
embedding model configured, no index is declared, since an index at the wrong width would accept
writes. Ensuring is idempotent: a matching index is left alone, and an index of a different shape is
reported as drift, not dropped.

### Changing the embedding model

Re-embed the stored messages with the new model:

```kotlin
messageReembedder.reembed(embeddingService)
```

`MessageReembedder` remakes the vector index from the same spec the startup catalog uses, so the two
cannot describe different indexes.

## User Implementation

Implement `StoredUser` for your user type. The `StoredUser` interface extends `User` from
`embabel-agent-api` with Drivine annotations for graph-database persistence (the same
annotations work on every supported database):

```kotlin
@NodeFragment(labels = ["User", "MyUser"])
data class MyUser(
    @NodeId override val id: String,
    override val displayName: String,
    override val username: String,
    override val email: String?
) : StoredUser
```

Register it with Drivine, so a `User` node with the `MyUser` label loads as a `MyUser`:

```kotlin
persistenceManager.registerSubtype(
    StoredUser::class.java,
    listOf("User", "MyUser"),   // the subtype's labels, in any order
    MyUser::class.java,
)
```

Drivine registers subtypes itself only for a sealed class or a base type annotated with
`@JsonSubTypes`. `StoredUser` is an interface applications implement, so it is neither, and an
implementation needs this call.

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
        enabled: true                # default: declare the message vector index
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
| `chatStoreConstraintSchema` | — | Uniqueness constraints and the `lastActivityAt` range index |
| `chatStoreVectorIndexSchema` | `Ai` bean + property | The message vector index, at the embedding model's width |
| `messageReembedder` | `ChatSessionRepository` | Re-embeds messages and remakes the vector index |
| `sessionEventAwaiter` | — | Waits on a session's persistence events |

Each of these can be overridden by defining your own bean of the same type.

## Testing

Integration tests use Testcontainers, on Neo4j:

```bash
mvn test
```

The Oracle tests are left out of a plain run, because the image is large and the database wants two
gigabytes. They start Oracle Free in a container:

```bash
ORACLE_TESTS=true mvn -pl embabel-chat-store test -Dtest=ChatSessionRepositoryOracleTest
```

## Dependencies

- `embabel-agent-api` - Core conversation interfaces
- `drivine` - graph persistence on Neo4j, FalkorDB, Memgraph and Oracle
- Spring Boot (optional, for auto-configuration)
