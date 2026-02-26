// Generated code - do not modify
package com.embabel.chat.store.model

import kotlin.Int
import kotlin.Unit
import kotlin.collections.List
import org.drivine.manager.GraphObjectManager
import org.drivine.query.dsl.GraphQuerySpec

public class NewMessageInSessionQueryDsl {
  public val session: SessionRefProperties = SessionRefProperties("session")

  public val message: AttributedMessageProperties = AttributedMessageProperties("message")

  public companion object {
    public val INSTANCE: NewMessageInSessionQueryDsl = NewMessageInSessionQueryDsl()
  }
}

public inline fun <reified T : NewMessageInSession> GraphObjectManager.loadAll(noinline
    spec: GraphQuerySpec<NewMessageInSessionQueryDsl>.() -> Unit): List<T> = loadAll(T::class.java,
    NewMessageInSessionQueryDsl.INSTANCE, spec)

public inline fun <reified T : NewMessageInSession> GraphObjectManager.deleteAll(noinline
    spec: GraphQuerySpec<NewMessageInSessionQueryDsl>.() -> Unit): Int = deleteAll(T::class.java,
    NewMessageInSessionQueryDsl.INSTANCE, spec)

context(builder: org.drivine.query.dsl.WhereBuilder<NewMessageInSessionQueryDsl>)
@Suppress("CONTEXT_RECEIVERS_DEPRECATED")
public val session: com.embabel.chat.store.model.SessionRefProperties
    get() = builder.queryObject.session

context(builder: org.drivine.query.dsl.OrderBuilder<NewMessageInSessionQueryDsl>)
@Suppress("CONTEXT_RECEIVERS_DEPRECATED")
public val session: com.embabel.chat.store.model.SessionRefProperties
    get() = builder.queryObject.session

context(builder: org.drivine.query.dsl.WhereBuilder<NewMessageInSessionQueryDsl>)
@Suppress("CONTEXT_RECEIVERS_DEPRECATED")
public val message: com.embabel.chat.store.model.AttributedMessageProperties
    get() = builder.queryObject.message

context(builder: org.drivine.query.dsl.OrderBuilder<NewMessageInSessionQueryDsl>)
@Suppress("CONTEXT_RECEIVERS_DEPRECATED")
public val message: com.embabel.chat.store.model.AttributedMessageProperties
    get() = builder.queryObject.message

