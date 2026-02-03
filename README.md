# Embabel Chat Store

Conversation persistence layer for Embabel Agent. Provides pluggable storage backends for chat sessions - in-memory for development/testing, Neo4j for production.

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
    ├── InMemoryConversationFactory  → InMemoryConversation (ephemeral)
    └── StoredConversationFactory    → StoredConversation (Neo4j)
```

### Storage Types

| Type | Class | Use Case |
|------|-------|----------|
| `IN_MEMORY` | `InMemoryConversation` | Testing, ephemeral chats |
| `STORED` | `StoredConversation` | Production, persistent chats |

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
(message:StoredMessage)-[:AUTHORED_BY]->(from:SessionUser)
(message:StoredMessage)-[:SENT_TO]->(to:SessionUser)
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

## Session User

Implement `SessionUser` for your user type:

```kotlin
@NodeFragment(labels = ["SessionUser", "MyUser"])
data class MyUser(
    @NodeId override val id: String,
    override val displayName: String,
    val email: String
) : SessionUser
```

Register with Drivine:

```kotlin
persistenceManager.registerSubtype(
    SessionUser::class.java,
    "MyUser|SessionUser",
    MyUser::class.java
)
```

## Spring Boot Auto-Configuration

Add the dependency and configure:

```yaml
embabel:
  chat:
    store:
      enabled: true  # default
```

Beans auto-configured:
- `storedConversationFactory` - for persistent conversations
- `inMemoryConversationFactory` - for ephemeral conversations
- `conversationFactoryProvider` - aggregates all factories

## Dependencies

- `embabel-agent-api` - Core conversation interfaces
- `drivine` - Neo4j graph persistence
- Spring Boot (optional, for auto-configuration)
