package lila.racer

import play.api.libs.json.*

import lila.common.LightUser
import lila.storm.StormJson
import lila.storm.StormSign

final class RacerJson(stormJson: StormJson, sign: StormSign, lightUserSync: LightUser.GetterSync):

  import StormJson.given

  given OWrites[RacerPlayer] = OWrites { p =>
    val user = p.userId flatMap lightUserSync
    Json
      .obj("name" -> p.name, "score" -> p.score)
      .add("userId", p.userId)
      .add("title", user.flatMap(_.title))
  }

  // full race data
  def data(race: RacerRace, player: RacerPlayer) =
    Json
      .obj(
        "race" -> Json
          .obj("id" -> race.id.value)
          .add("lobby", race.isLobby),
        "player"  -> player,
        "puzzles" -> race.puzzles
      )
      .add("owner", race.owner == player.id) ++ state(race)

  // socket updates
  def state(race: RacerRace) = Json
    .obj("players" -> race.players)
    .add("startsIn", race.startsInMillis)
