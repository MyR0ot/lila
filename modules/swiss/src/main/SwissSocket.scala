package lila.swiss

import scala.concurrent.duration.*

import lila.hub.actorApi.team.IsLeader
import lila.hub.LateMultiThrottler
import lila.hub.LightTeam.TeamID
import lila.room.RoomSocket.{ Protocol as RP, * }
import lila.socket.RemoteSocket.{ Protocol as P, * }
import lila.socket.Socket.makeMessage

final private class SwissSocket(
    remoteSocketApi: lila.socket.RemoteSocket,
    chat: lila.chat.ChatApi,
    teamOf: SwissId => Fu[Option[TeamID]]
)(using
    ec: scala.concurrent.ExecutionContext,
    system: akka.actor.ActorSystem,
    scheduler: akka.actor.Scheduler,
    mode: play.api.Mode
):

  private val reloadThrottler = LateMultiThrottler(executionTimeout = none, logger = logger)

  def reload(id: SwissId): Unit =
    reloadThrottler ! LateMultiThrottler.work(
      id = id,
      run = fuccess {
        send(RP.Out.tellRoom(RoomId(id), makeMessage("reload")))
      },
      delay = 1.seconds.some
    )

  lazy val rooms = makeRoomMap(send)

  subscribeChat(rooms, _.Swiss)

  private lazy val handler: Handler =
    roomHandler(
      rooms,
      chat,
      logger,
      roomId => _.Swiss(SwissId(roomId.value)).some,
      localTimeout = Some { (roomId, modId, _) =>
        teamOf(SwissId(roomId.value)) flatMap {
          _ ?? { teamId =>
            lila.common.Bus.ask[Boolean]("teamIsLeader") { IsLeader(teamId, modId, _) }
          }
        }
      },
      chatBusChan = _.Swiss
    )

  private lazy val send: String => Unit = remoteSocketApi.makeSender("swiss-out").apply

  remoteSocketApi.subscribe("swiss-in", RP.In.reader)(
    handler orElse remoteSocketApi.baseHandler
  ) >>- send(P.Out.boot)
