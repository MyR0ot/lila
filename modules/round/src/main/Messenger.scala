package lila.round

import lila.chat.{ Chat, ChatApi, ChatTimeout }
import lila.game.Game
import lila.hub.actorApi.shutup.PublicSource
import lila.user.User

final class Messenger(api: ChatApi):

  def system(game: Game, message: String): Unit =
    system(persistent = true)(game, message)

  def volatile(game: Game, message: String): Unit =
    system(persistent = false)(game, message)

  def apply(game: Game, message: Messenger.SystemMessage): Unit = message match
    case Messenger.Persistent(msg) => system(persistent = true)(game, msg)
    case Messenger.Volatile(msg)   => system(persistent = false)(game, msg)

  def system(persistent: Boolean)(game: Game, message: String): Unit = if (game.nonAi) {
    api.userChat.volatile(chatWatcherId(Chat.Id(game.id)), message, _.Round)
    if (persistent) api.userChat.system(Chat.Id(game.id), message, _.Round)
    else api.userChat.volatile(Chat.Id(game.id), message, _.Round)
  }.unit

  def watcher(gameId: GameId, userId: User.ID, text: String) =
    api.userChat.write(gameWatcherId(gameId), userId, text, PublicSource.Watcher(gameId).some, _.Round)

  private val whisperCommands = List("/whisper ", "/w ", "/W ")

  def owner(gameId: GameId, userId: User.ID, text: String): Funit =
    whisperCommands.collectFirst {
      case command if text startsWith command =>
        val source = PublicSource.Watcher(gameId)
        api.userChat.write(gameWatcherId(gameId), userId, text drop command.length, source.some, _.Round)
    } getOrElse {
      !text.startsWith("/") ?? // mistyped command?
        api.userChat.write(Chat.Id(gameId), userId, text, publicSource = none, _.Round)
    }

  def owner(game: Game, anonColor: chess.Color, text: String): Funit =
    (game.fromFriend || presets.contains(text)) ??
      api.playerChat.write(Chat.Id(game.id), anonColor, text, _.Round)

  def timeout(chatId: Chat.Id, modId: User.ID, suspect: User.ID, reason: String, text: String): Funit =
    ChatTimeout.Reason(reason) ?? { r =>
      api.userChat.timeout(chatId, modId, suspect, r, ChatTimeout.Scope.Global, text, _.Round)
    }

  private val presets = Set(
    "Hello",
    "Good luck",
    "Have fun!",
    "You too!",
    "Good game",
    "Well played",
    "Thank you",
    "I've got to go",
    "Bye!"
  )

  private def chatWatcherId(chatId: Chat.Id) = Chat.Id(s"$chatId/w")
  private def gameWatcherId(gameId: GameId) = Chat.Id(s"$gameId/w")

private object Messenger:

  sealed trait SystemMessage { val msg: String }
  case class Persistent(msg: String) extends SystemMessage
  case class Volatile(msg: String)   extends SystemMessage
