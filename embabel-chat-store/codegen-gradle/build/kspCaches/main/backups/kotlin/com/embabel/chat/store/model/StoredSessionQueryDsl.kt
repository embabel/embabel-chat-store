// Generated code - do not modify
package com.embabel.chat.store.model

import kotlin.Int
import kotlin.Unit
import kotlin.collections.List
import org.drivine.manager.GraphObjectManager
import org.drivine.query.dsl.GraphQuerySpec

public class StoredSessionQueryDsl {
  public val session: SessionDataProperties = SessionDataProperties("session")

  public val owner: StoredUserProperties = StoredUserProperties("owner")

  public val messages: SimpleStoredMessageProperties = SimpleStoredMessageProperties("messages")

  public companion object {
    public val INSTANCE: StoredSessionQueryDsl = StoredSessionQueryDsl()
  }
}

public inline fun <reified T : StoredSession> GraphObjectManager.loadAll(noinline
    spec: GraphQuerySpec<StoredSessionQueryDsl>.() -> Unit): List<T> = loadAll(T::class.java,
    StoredSessionQueryDsl.INSTANCE, spec)

public inline fun <reified T : StoredSession> GraphObjectManager.deleteAll(noinline
    spec: GraphQuerySpec<StoredSessionQueryDsl>.() -> Unit): Int = deleteAll(T::class.java,
    StoredSessionQueryDsl.INSTANCE, spec)

context(builder: org.drivine.query.dsl.WhereBuilder<StoredSessionQueryDsl>)
@Suppress("CONTEXT_RECEIVERS_DEPRECATED")
public val session: com.embabel.chat.store.model.SessionDataProperties
    get() = builder.queryObject.session

context(builder: org.drivine.query.dsl.OrderBuilder<StoredSessionQueryDsl>)
@Suppress("CONTEXT_RECEIVERS_DEPRECATED")
public val session: com.embabel.chat.store.model.SessionDataProperties
    get() = builder.queryObject.session

context(builder: org.drivine.query.dsl.WhereBuilder<StoredSessionQueryDsl>)
@Suppress("CONTEXT_RECEIVERS_DEPRECATED")
public val owner: com.embabel.chat.store.model.StoredUserProperties
    get() = builder.queryObject.owner

context(builder: org.drivine.query.dsl.OrderBuilder<StoredSessionQueryDsl>)
@Suppress("CONTEXT_RECEIVERS_DEPRECATED")
public val owner: com.embabel.chat.store.model.StoredUserProperties
    get() = builder.queryObject.owner

context(builder: org.drivine.query.dsl.WhereBuilder<StoredSessionQueryDsl>)
@Suppress("CONTEXT_RECEIVERS_DEPRECATED")
public val messages: com.embabel.chat.store.model.SimpleStoredMessageProperties
    get() = builder.queryObject.messages

context(builder: org.drivine.query.dsl.OrderBuilder<StoredSessionQueryDsl>)
@Suppress("CONTEXT_RECEIVERS_DEPRECATED")
public val messages: com.embabel.chat.store.model.SimpleStoredMessageProperties
    get() = builder.queryObject.messages

