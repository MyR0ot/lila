package lila.swiss

import akka.stream.scaladsl.*
import reactivemongo.api.bson.*
import scala.concurrent.duration.*

import lila.db.dsl.{ *, given }

case class SwissStats(
    games: Int = 0,
    whiteWins: Int = 0,
    blackWins: Int = 0,
    draws: Int = 0,
    byes: Int = 0,
    absences: Int = 0,
    averageRating: Int = 0
)

final class SwissStatsApi(
    mongo: SwissMongo,
    sheetApi: SwissSheetApi,
    mongoCache: lila.memo.MongoCache.Api
)(using
    ec: scala.concurrent.ExecutionContext,
    mat: akka.stream.Materializer
):

  import BsonHandlers.given

  def apply(swiss: Swiss): Fu[Option[SwissStats]] =
    swiss.isFinished ?? cache.get(swiss.id).dmap(some).dmap(_.filter(_.games > 0))

  private given BSONDocumentHandler[SwissStats] = Macros.handler

  private val cache = mongoCache[SwissId, SwissStats](64, "swiss:stats", 60 days, identity) { loader =>
    _.expireAfterAccess(5 seconds)
      .maximumSize(256)
      .buildAsyncFuture(loader(fetch))
  }

  private def fetch(id: SwissId): Fu[SwissStats] =
    mongo.swiss.byId[Swiss](id) flatMap {
      _.filter(_.nbPlayers > 0).fold(fuccess(SwissStats())) { swiss =>
        sheetApi
          .source(swiss, sort = $empty)
          .toMat(Sink.fold(SwissStats()) { case (stats, (player, pairings, sheet)) =>
            pairings.values.foldLeft((0, 0, 0, 0)) { case ((games, whiteWins, blackWins, draws), pairing) =>
              (
                games + 1,
                whiteWins + pairing.whiteWins.??(1),
                blackWins + pairing.blackWins.??(1),
                draws + pairing.isDraw.??(1)
              )
            } match {
              case (games, whiteWins, blackWins, draws) =>
                sheet.outcomes.foldLeft((0, 0)) { case ((byes, absences), outcome) =>
                  (
                    byes + (outcome == SwissSheet.Bye).??(1),
                    absences + (outcome == SwissSheet.Absent).??(1)
                  )
                } match {
                  case (byes, absences) =>
                    stats.copy(
                      games = stats.games + games,
                      whiteWins = stats.whiteWins + whiteWins,
                      blackWins = stats.blackWins + blackWins,
                      draws = stats.draws + draws,
                      byes = stats.byes + byes,
                      absences = stats.absences + absences,
                      averageRating = stats.averageRating + player.rating
                    )
                }
            }
          })(Keep.right)
          .run()
          .dmap { s => s.copy(games = s.games / 2, averageRating = s.averageRating / swiss.nbPlayers) }
      }
    }
