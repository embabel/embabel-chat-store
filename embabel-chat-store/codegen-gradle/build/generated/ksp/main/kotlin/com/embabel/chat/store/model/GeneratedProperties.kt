// Generated code - do not modify
package com.embabel.chat.store.model

import com.embabel.chat.MessageRole
import java.time.Instant
import kotlin.Any
import kotlin.String
import kotlin.collections.Map
import org.drivine.query.dsl.NodeReference
import org.drivine.query.dsl.PropertyReference
import org.drivine.query.dsl.StringPropertyReference

public class MessageDataProperties(
  private val alias: String,
) : NodeReference {
  override val nodeAlias: String
    get() = alias

  public val messageId: StringPropertyReference =
      org.drivine.query.dsl.StringPropertyReference(alias, "messageId")

  public val role: PropertyReference<MessageRole> =
      org.drivine.query.dsl.PropertyReference<com.embabel.chat.MessageRole>(alias, "role")

  public val content: StringPropertyReference = org.drivine.query.dsl.StringPropertyReference(alias,
      "content")

  public val createdAt: PropertyReference<Instant> =
      org.drivine.query.dsl.PropertyReference<java.time.Instant>(alias, "createdAt")

  public val metadata: PropertyReference<Map<String, Any>> =
      org.drivine.query.dsl.PropertyReference<kotlin.collections.Map<kotlin.String,
      kotlin.Any>>(alias, "metadata")

  public val narration: StringPropertyReference =
      org.drivine.query.dsl.StringPropertyReference(alias, "narration")
}

public class StoredUserProperties(
  private val alias: String,
) : NodeReference {
  override val nodeAlias: String
    get() = alias

  public val id: StringPropertyReference = org.drivine.query.dsl.StringPropertyReference(alias,
      "id")

  public val displayName: StringPropertyReference =
      org.drivine.query.dsl.StringPropertyReference(alias, "displayName")

  public val username: StringPropertyReference =
      org.drivine.query.dsl.StringPropertyReference(alias, "username")

  public val email: StringPropertyReference = org.drivine.query.dsl.StringPropertyReference(alias,
      "email")
}

public class UserRefProperties(
  private val alias: String,
) : NodeReference {
  override val nodeAlias: String
    get() = alias

  public val id: StringPropertyReference = org.drivine.query.dsl.StringPropertyReference(alias,
      "id")
}

public class SessionRefProperties(
  private val alias: String,
) : NodeReference {
  override val nodeAlias: String
    get() = alias

  public val sessionId: StringPropertyReference =
      org.drivine.query.dsl.StringPropertyReference(alias, "sessionId")
}

public class SessionDataProperties(
  private val alias: String,
) : NodeReference {
  override val nodeAlias: String
    get() = alias

  public val sessionId: StringPropertyReference =
      org.drivine.query.dsl.StringPropertyReference(alias, "sessionId")

  public val title: StringPropertyReference = org.drivine.query.dsl.StringPropertyReference(alias,
      "title")

  public val createdAt: PropertyReference<Instant> =
      org.drivine.query.dsl.PropertyReference<java.time.Instant>(alias, "createdAt")

  public val metadata: PropertyReference<Map<String, Any>> =
      org.drivine.query.dsl.PropertyReference<kotlin.collections.Map<kotlin.String,
      kotlin.Any>>(alias, "metadata")
}

public class AttributedMessageProperties(
  private val alias: String,
) : NodeReference {
  override val nodeAlias: String
    get() = alias

  public val message: MessageDataProperties = MessageDataProperties(alias)

  public val author: UserRefProperties = UserRefProperties("${alias}_author")

  public val recipient: UserRefProperties = UserRefProperties("${alias}_recipient")
}

public class SimpleStoredMessageProperties(
  private val alias: String,
) : NodeReference {
  override val nodeAlias: String
    get() = alias

  public val message: MessageDataProperties = MessageDataProperties(alias)

  public val author: StoredUserProperties = StoredUserProperties("${alias}_author")

  public val recipient: StoredUserProperties = StoredUserProperties("${alias}_recipient")
}
