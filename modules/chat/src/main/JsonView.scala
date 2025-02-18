package lila.chat

import play.api.libs.json.*

import lila.common.LightUser
import lila.common.Json.{ *, given }

object JsonView:

  import writers.{ *, given }

  lazy val timeoutReasons = Json toJson ChatTimeout.Reason.all

  def apply(chat: AnyChat): JsValue = chat match
    case c: MixedChat => mixedChatWriter writes c
    case c: UserChat  => userChatWriter writes c

  def apply(line: Line): JsObject = lineWriter writes line

  def userModInfo(u: UserModInfo)(implicit lightUser: LightUser.GetterSync) =
    lila.user.JsonView.modWrites.writes(u.user) ++ Json.obj(
      "history" -> u.history
    )

  def mobile(chat: AnyChat, writeable: Boolean = true) =
    Json.obj(
      "lines"     -> apply(chat),
      "writeable" -> writeable
    )

  def boardApi(chat: UserChat) = JsArray {
    chat.lines collect {
      case UserLine(name, _, _, text, troll, del) if !troll && !del =>
        Json.obj("text" -> text, "user" -> name)
    }
  }

  object writers:

    given Writes[Chat.Id] = stringIsoWriter

    given Writes[ChatTimeout.Reason] = OWrites[ChatTimeout.Reason] { r =>
      Json.obj("key" -> r.key, "name" -> r.name)
    }

    implicit def timeoutEntryWriter(using lightUser: LightUser.GetterSync): OWrites[ChatTimeout.UserEntry] =
      OWrites[ChatTimeout.UserEntry] { e =>
        Json.obj(
          "reason" -> e.reason.key,
          "mod"    -> lightUser(e.mod).fold("?")(_.name),
          "date"   -> e.createdAt
        )
      }

    given mixedChatWriter: Writes[MixedChat] = Writes[MixedChat] { c =>
      JsArray(c.lines map lineWriter.writes)
    }

    given userChatWriter: Writes[UserChat] = Writes[UserChat] { c =>
      JsArray(c.lines map userLineWriter.writes)
    }

    private[chat] val lineWriter: OWrites[Line] = OWrites[Line] {
      case l: UserLine   => userLineWriter writes l
      case l: PlayerLine => playerLineWriter writes l
    }

    val userLineWriter: OWrites[UserLine] = OWrites[UserLine] { l =>
      Json
        .obj(
          "u" -> l.username,
          "t" -> l.text
        )
        .add("r" -> l.troll)
        .add("d" -> l.deleted)
        .add("p" -> l.patron)
        .add("title" -> l.title)
    }

    val playerLineWriter: OWrites[PlayerLine] = OWrites[PlayerLine] { l =>
      Json.obj(
        "c" -> l.color.name,
        "t" -> l.text
      )
    }
