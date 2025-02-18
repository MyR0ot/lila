package lila.simul

import chess.{ Clock, Status }
import chess.variant.Variant
import org.joda.time.DateTime
import reactivemongo.api.bson.*

import lila.db.BSON
import lila.db.dsl.{ *, given }
import lila.user.User

final private[simul] class SimulRepo(val coll: Coll)(using ec: scala.concurrent.ExecutionContext):

  import lila.game.BSONHandlers.given
  private given BSONHandler[SimulStatus] = tryHandler(
    { case BSONInteger(v) => SimulStatus(v) toTry s"No such simul status: $v" },
    x => BSONInteger(x.id)
  )
  private given BSONHandler[Variant]                = variantByIdHandler
  private given BSONDocumentHandler[Clock.Config]   = Macros.handler
  private given BSONDocumentHandler[SimulClock]     = Macros.handler
  private given BSONDocumentHandler[SimulPlayer]    = Macros.handler
  private given BSONDocumentHandler[SimulApplicant] = Macros.handler
  private given BSON[SimulPairing] with
    def reads(r: BSON.Reader) =
      SimulPairing(
        player = r.get[SimulPlayer]("player"),
        gameId = r.get[GameId]("gameId"),
        status = r.get[Status]("status"),
        wins = r boolO "wins",
        hostColor = r.strO("hostColor").flatMap(chess.Color.fromName) | chess.White
      )
    def writes(w: BSON.Writer, o: SimulPairing) =
      $doc(
        "player"    -> o.player,
        "gameId"    -> o.gameId,
        "status"    -> o.status,
        "wins"      -> o.wins,
        "hostColor" -> o.hostColor.name
      )

  private given BSONDocumentHandler[Simul] = Macros.handler

  private val createdSelect  = $doc("status" -> SimulStatus.Created.id)
  private val startedSelect  = $doc("status" -> SimulStatus.Started.id)
  private val finishedSelect = $doc("status" -> SimulStatus.Finished.id)
  private val createdSort    = $sort desc "createdAt"

  def find(id: Simul.ID): Fu[Option[Simul]] =
    coll.byId[Simul](id)

  def byIds(ids: List[Simul.ID]): Fu[List[Simul]] =
    coll.byStringIds[Simul](ids)

  def exists(id: Simul.ID): Fu[Boolean] =
    coll.exists($id(id))

  def findStarted(id: Simul.ID): Fu[Option[Simul]] =
    find(id) map (_ filter (_.isStarted))

  def findCreated(id: Simul.ID): Fu[Option[Simul]] =
    find(id) map (_ filter (_.isCreated))

  def findPending(hostId: User.ID): Fu[List[Simul]] =
    coll.list[Simul](createdSelect ++ $doc("hostId" -> hostId))

  def byTeamLeaders(teamId: String, hostIds: Seq[User.ID]): Fu[List[Simul]] =
    coll
      .find(
        createdSelect ++
          $doc("hostId" $in hostIds, "team" $in List(BSONString(teamId)))
      )
      .hint(coll hint $doc("hostId" -> 1))
      .cursor[Simul]()
      .listAll()

  def hostId(id: Simul.ID): Fu[Option[User.ID]] =
    coll.primitiveOne[User.ID]($id(id), "hostId")

  private val featurableSelect = $doc("featurable" -> true)

  def allCreatedFeaturable: Fu[List[Simul]] =
    coll
      .find(
        // hits partial index hostSeenAt_-1
        createdSelect ++ featurableSelect ++ $doc(
          "hostSeenAt" $gte DateTime.now.minusSeconds(12),
          "createdAt" $gte DateTime.now.minusHours(1)
        )
      )
      .sort(createdSort)
      .hint(coll hint $doc("hostSeenAt" -> -1))
      .cursor[Simul]()
      .list(100) map {
      _.foldLeft(List.empty[Simul]) {
        case (acc, sim) if acc.exists(_.hostId == sim.hostId) => acc
        case (acc, sim)                                       => sim :: acc
      }.reverse
    }

  def allStarted: Fu[List[Simul]] =
    coll
      .find(startedSelect)
      .sort(createdSort)
      .cursor[Simul]()
      .list(100)

  def allFinishedFeaturable(max: Int): Fu[List[Simul]] =
    coll
      .find(finishedSelect ++ featurableSelect)
      .sort($sort desc "finishedAt")
      .cursor[Simul]()
      .list(max)

  def allNotFinished =
    coll.list[Simul]($doc("status" $ne SimulStatus.Finished.id))

  def create(simul: Simul): Funit =
    coll.insert.one(simul).void

  def update(simul: Simul) =
    coll.update
      .one(
        $id(simul.id),
        $set(bsonWriteObjTry[Simul](simul).get) ++
          simul.estimatedStartAt.isEmpty ?? ($unset("estimatedStartAt"))
      )
      .void

  def remove(simul: Simul) =
    coll.delete.one($id(simul.id)).void

  def setHostGameId(simul: Simul, gameId: String) =
    coll.update
      .one(
        $id(simul.id),
        $set("hostGameId" -> gameId)
      )
      .void

  def setHostSeenNow(simul: Simul) =
    coll.update
      .one(
        $id(simul.id),
        $set("hostSeenAt" -> DateTime.now)
      )
      .void

  def setText(simul: Simul, text: String) =
    coll.update
      .one(
        $id(simul.id),
        $set("text" -> text)
      )
      .void

  def cleanup =
    coll.delete.one(
      createdSelect ++ $doc(
        "createdAt" -> $doc("$lt" -> (DateTime.now minusMinutes 60))
      )
    )
