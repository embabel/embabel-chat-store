// Generated code - do not modify
package com.embabel.chat.store.model

import kotlin.Int
import kotlin.Unit
import kotlin.collections.List
import org.drivine.manager.GraphObjectManager
import org.drivine.query.dsl.GraphQuerySpec

public class SimpleStoredMessageQueryDsl {
  public val message: MessageDataProperties = MessageDataProperties("message")

  public val author: StoredUserProperties = StoredUserProperties("author")

  public val recipient: StoredUserProperties = StoredUserProperties("recipient")

  public companion object {
    public val INSTANCE: SimpleStoredMessageQueryDsl = SimpleStoredMessageQueryDsl()
  }
}

public inline fun <reified T : SimpleStoredMessage> GraphObjectManager.loadAll(noinline
    spec: GraphQuerySpec<SimpleStoredMessageQueryDsl>.() -> Unit): List<T> = loadAll(T::class.java,
    SimpleStoredMessageQueryDsl.INSTANCE, spec)

public inline fun <reified T : SimpleStoredMessage> GraphObjectManager.deleteAll(noinline
    spec: GraphQuerySpec<SimpleStoredMessageQueryDsl>.() -> Unit): Int = deleteAll(T::class.java,
    SimpleStoredMessageQueryDsl.INSTANCE, spec)

context(builder: org.drivine.query.dsl.WhereBuilder<SimpleStoredMessageQueryDsl>)
@Suppress("CONTEXT_RECEIVERS_DEPRECATED")
public val message: com.embabel.chat.store.model.MessageDataProperties
    get() = builder.queryObject.message

context(builder: org.drivine.query.dsl.OrderBuilder<SimpleStoredMessageQueryDsl>)
@Suppress("CONTEXT_RECEIVERS_DEPRECATED")
public val message: com.embabel.chat.store.model.MessageDataProperties
    get() = builder.queryObject.message

context(builder: org.drivine.query.dsl.WhereBuilder<SimpleStoredMessageQueryDsl>)
@Suppress("CONTEXT_RECEIVERS_DEPRECATED")
public val author: com.embabel.chat.store.model.StoredUserProperties
    get() = builder.queryObject.author

context(builder: org.drivine.query.dsl.OrderBuilder<SimpleStoredMessageQueryDsl>)
@Suppress("CONTEXT_RECEIVERS_DEPRECATED")
public val author: com.embabel.chat.store.model.StoredUserProperties
    get() = builder.queryObject.author

context(builder: org.drivine.query.dsl.WhereBuilder<SimpleStoredMessageQueryDsl>)
@Suppress("CONTEXT_RECEIVERS_DEPRECATED")
public val recipient: com.embabel.chat.store.model.StoredUserProperties
    get() = builder.queryObject.recipient

context(builder: org.drivine.query.dsl.OrderBuilder<SimpleStoredMessageQueryDsl>)
@Suppress("CONTEXT_RECEIVERS_DEPRECATED")
public val recipient: com.embabel.chat.store.model.StoredUserProperties
    get() = builder.queryObject.recipient

