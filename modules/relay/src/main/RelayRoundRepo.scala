package lila.relay

import org.joda.time.DateTime
import reactivemongo.api.bson.*
import reactivemongo.akkastream.{ cursorProducer, AkkaStreamCursor }
import reactivemongo.api.ReadPreference

import lila.db.dsl.{ *, given }

final private class RelayRoundRepo(val coll: Coll)(using ec: scala.concurrent.ExecutionContext):

  import BSONHandlers.given

  def byTourOrdered(tour: RelayTour): Fu[List[RelayRound]] =
    coll
      .find(selectors.tour(tour.id))
      .sort(sort.chrono)
      .cursor[RelayRound]()
      .list(RelayTour.maxRelays)

  def idsByTourOrdered(tour: RelayTour): Fu[List[RelayRound.Id]] =
    coll
      .find(selectors.tour(tour.id), $id(true).some)
      .sort(sort.chrono)
      .cursor[Bdoc]()
      .list(RelayTour.maxRelays)
      .map(_.flatMap(_.getAsOpt[RelayRound.Id]("_id")))

  def lastByTour(tour: RelayTour): Fu[Option[RelayRound]] =
    coll
      .find(selectors tour tour.id)
      .sort(sort.reverseChrono)
      .one[RelayRound]

  private[relay] object sort:
    val chrono        = $doc("createdAt" -> 1)
    val reverseChrono = $doc("createdAt" -> -1)
    val start         = $doc("startedAt" -> -1, "startsAt" -> -1, "name" -> -1)

  private[relay] object selectors:
    def tour(id: RelayTour.Id) = $doc("tourId" -> id)
