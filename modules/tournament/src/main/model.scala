package lila.tournament

import play.api.i18n.Lang

import lila.hub.LightTeam.TeamID
import lila.user.User

final class LeaderboardRepo(val coll: lila.db.dsl.Coll)

case class TournamentTop(value: List[Player]) extends AnyVal

case class TourMiniView(
    tour: Tournament,
    top: Option[TournamentTop],
    teamVs: Option[TeamBattle.TeamVs]
):
  def tourAndTeamVs = TourAndTeamVs(tour, teamVs)

case class TourAndTeamVs(tour: Tournament, teamVs: Option[TeamBattle.TeamVs])

case class GameView(
    tour: Tournament,
    teamVs: Option[TeamBattle.TeamVs],
    ranks: Option[GameRanks],
    top: Option[TournamentTop]
):
  def tourAndTeamVs = TourAndTeamVs(tour, teamVs)

case class MyInfo(rank: Int, withdraw: Boolean, gameId: Option[GameId], teamId: Option[TeamID]):
  def page = (rank + 9) / 10

case class VisibleTournaments(
    created: List[Tournament],
    started: List[Tournament],
    finished: List[Tournament]
):

  def unfinished = created ::: started

  def all = started ::: created ::: finished

  def add(tours: List[Tournament]) =
    copy(
      created = tours.filter(_.isCreated) ++ created,
      started = tours.filter(_.isStarted) ++ started
    )

case class PlayerInfoExt(
    userId: User.ID,
    player: Player,
    recentPovs: List[lila.game.LightPov]
)

case class FullRanking(ranking: Map[User.ID, Int], playerIndex: Array[TourPlayerId])

case class GameRanks(whiteRank: Int, blackRank: Int)

case class RankedPairing(pairing: Pairing, rank1: Int, rank2: Int):

  def bestRank = rank1 min rank2
  // def rankSum = rank1 + rank2

  def bestColor = chess.Color.fromWhite(rank1 < rank2)

object RankedPairing:

  def apply(ranking: Ranking)(pairing: Pairing): Option[RankedPairing] =
    for {
      r1 <- ranking get pairing.user1
      r2 <- ranking get pairing.user2
    } yield RankedPairing(pairing, r1 + 1, r2 + 1)

case class RankedPlayer(rank: Int, player: Player):

  def is(other: RankedPlayer) = player is other.player

  def withColorHistory(getHistory: TourPlayerId => ColorHistory) =
    RankedPlayerWithColorHistory(rank, player, getHistory(player.id))

  override def toString = s"$rank. ${player.userId}[${player.rating}]"

object RankedPlayer:

  def apply(ranking: Ranking)(player: Player): Option[RankedPlayer] =
    ranking get player.userId map { rank =>
      RankedPlayer(rank + 1, player)
    }

case class RankedPlayerWithColorHistory(rank: Int, player: Player, colorHistory: ColorHistory):

  def is(other: RankedPlayer) = player is other.player

  override def toString = s"$rank. ${player.userId}[${player.rating}]"

case class FeaturedGame(
    game: lila.game.Game,
    white: RankedPlayer,
    black: RankedPlayer
)

final class GetTourName(cache: lila.memo.Syncache[(Tournament.ID, Lang), Option[String]]):
  def sync(id: Tournament.ID)(using lang: Lang)               = cache.sync(id -> lang)
  def async(id: Tournament.ID)(using lang: Lang)              = cache.async(id -> lang)
  def preload(ids: Iterable[Tournament.ID])(using lang: Lang) = cache.preloadMany(ids.map(_ -> lang).toSeq)
