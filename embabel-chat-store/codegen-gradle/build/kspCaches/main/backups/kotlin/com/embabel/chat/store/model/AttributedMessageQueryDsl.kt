// Generated code - do not modify
package com.embabel.chat.store.model

import kotlin.Int
import kotlin.Unit
import kotlin.collections.List
import org.drivine.manager.GraphObjectManager
import org.drivine.query.dsl.GraphQuerySpec

public class AttributedMessageQueryDsl {
  public val message: MessageDataProperties = MessageDataProperties("message")

  public val author: UserRefProperties = UserRefProperties("author")

  public val recipient: UserRefProperties = UserRefProperties("recipient")

  public companion object {
    public val INSTANCE: AttributedMessageQueryDsl = AttributedMessageQueryDsl()
  }
}

public inline fun <reified T : AttributedMessage> GraphObjectManager.loadAll(noinline
    spec: GraphQuerySpec<AttributedMessageQueryDsl>.() -> Unit): List<T> = loadAll(T::class.java,
    AttributedMessageQueryDsl.INSTANCE, spec)

public inline fun <reified T : AttributedMessage> GraphObjectManager.deleteAll(noinline
    spec: GraphQuerySpec<AttributedMessageQueryDsl>.() -> Unit): Int = deleteAll(T::class.java,
    AttributedMessageQueryDsl.INSTANCE, spec)

context(builder: org.drivine.query.dsl.WhereBuilder<AttributedMessageQueryDsl>)
@Suppress("CONTEXT_RECEIVERS_DEPRECATED")
public val message: com.embabel.chat.store.model.MessageDataProperties
    get() = builder.queryObject.message

context(builder: org.drivine.query.dsl.OrderBuilder<AttributedMessageQueryDsl>)
@Suppress("CONTEXT_RECEIVERS_DEPRECATED")
public val message: com.embabel.chat.store.model.MessageDataProperties
    get() = builder.queryObject.message

context(builder: org.drivine.query.dsl.WhereBuilder<AttributedMessageQueryDsl>)
@Suppress("CONTEXT_RECEIVERS_DEPRECATED")
public val author: com.embabel.chat.store.model.UserRefProperties
    get() = builder.queryObject.author

context(builder: org.drivine.query.dsl.OrderBuilder<AttributedMessageQueryDsl>)
@Suppress("CONTEXT_RECEIVERS_DEPRECATED")
public val author: com.embabel.chat.store.model.UserRefProperties
    get() = builder.queryObject.author

context(builder: org.drivine.query.dsl.WhereBuilder<AttributedMessageQueryDsl>)
@Suppress("CONTEXT_RECEIVERS_DEPRECATED")
public val recipient: com.embabel.chat.store.model.UserRefProperties
    get() = builder.queryObject.recipient

context(builder: org.drivine.query.dsl.OrderBuilder<AttributedMessageQueryDsl>)
@Suppress("CONTEXT_RECEIVERS_DEPRECATED")
public val recipient: com.embabel.chat.store.model.UserRefProperties
    get() = builder.queryObject.recipient

