package lila.game

import cats.implicits.*
import chess.Color
import scala.util.chaining.*

import lila.user.User

case class PlayerUser(id: String, rating: Int, ratingDiff: Option[Int])

case class Player(
    id: GamePlayerId,
    color: Color,
    aiLevel: Option[Int],
    isWinner: Option[Boolean] = None,
    isOfferingDraw: Boolean = false,
    proposeTakebackAt: Int = 0, // ply when takeback was proposed
    userId: Player.UserId = None,
    rating: Option[Int] = None,
    ratingDiff: Option[Int] = None,
    provisional: Boolean = false,
    blurs: Blurs = Blurs.zeroBlurs.zero,
    berserk: Boolean = false,
    name: Option[String] = None
):

  def playerUser =
    userId flatMap { uid =>
      rating map { PlayerUser(uid, _, ratingDiff) }
    }

  def isAi = aiLevel.isDefined

  def isHuman = !isAi

  def hasUser = userId.isDefined

  def isUser(u: User) = userId.fold(false)(_ == u.id)

  def userInfos: Option[Player.UserInfo] =
    (userId, rating) mapN { (id, ra) =>
      Player.UserInfo(id, ra, provisional)
    }

  def wins = isWinner getOrElse false

  def goBerserk = copy(berserk = true)

  def finish(winner: Boolean) = copy(isWinner = winner option true)

  def offerDraw = copy(isOfferingDraw = true)

  def removeDrawOffer = copy(isOfferingDraw = false)

  def proposeTakeback(ply: Int) = copy(proposeTakebackAt = ply)

  def removeTakebackProposition = copy(proposeTakebackAt = 0)

  def isProposingTakeback = proposeTakebackAt != 0

  def nameSplit: Option[(String, Option[Int])] =
    name map {
      case Player.nameSplitRegex(n, r) => n.trim -> r.toIntOption
      case n                           => n      -> none
    }

  def before(other: Player) =
    ((rating, id), (other.rating, other.id)) match
      case ((Some(a), _), (Some(b), _)) if a != b => a > b
      case ((Some(_), _), (None, _))              => true
      case ((None, _), (Some(_), _))              => false
      case ((_, a), (_, b))                       => a < b

  def ratingAfter = rating map (_ + ~ratingDiff)

  def stableRating = rating ifFalse provisional

  def stableRatingAfter = stableRating map (_ + ~ratingDiff)

object Player:

  private val nameSplitRegex = """([^(]++)\((\d++)\)""".r

  def make(
      color: Color,
      aiLevel: Option[Int] = None
  ): Player =
    Player(
      id = IdGenerator.player(color),
      color = color,
      aiLevel = aiLevel
    )

  def make(
      color: Color,
      userPerf: (User.ID, lila.rating.Perf)
  ): Player =
    make(
      color = color,
      userId = userPerf._1,
      rating = userPerf._2.intRating,
      provisional = userPerf._2.glicko.provisional
    )

  def make(
      color: Color,
      userId: User.ID,
      rating: Int,
      provisional: Boolean
  ): Player =
    Player(
      id = IdGenerator.player(color),
      color = color,
      aiLevel = none,
      userId = userId.some,
      rating = rating.some,
      provisional = provisional
    )

  def make(
      color: Color,
      user: Option[User],
      perfPicker: lila.user.Perfs => lila.rating.Perf
  ): Player =
    user.fold(make(color)) { u =>
      make(color, (u.id, perfPicker(u.perfs)))
    }

  def makeImported(
      color: Color,
      name: Option[String],
      rating: Option[Int]
  ): Player =
    Player(
      id = IdGenerator.player(color),
      color = color,
      aiLevel = none,
      name = name orElse "?".some,
      rating = rating
    )

  case class HoldAlert(ply: Int, mean: Int, sd: Int):
    def suspicious = HoldAlert.suspicious(ply)
  object HoldAlert:
    type Map = Color.Map[Option[HoldAlert]]
    val emptyMap: Map                 = Color.Map(none, none)
    def suspicious(ply: Int): Boolean = ply >= 16 && ply <= 40
    def suspicious(m: Map): Boolean   = m exists { _ exists (_.suspicious) }

  case class UserInfo(id: String, rating: Int, provisional: Boolean)

  import reactivemongo.api.bson.*
  import lila.db.BSON

  given BSONDocumentHandler[HoldAlert] = Macros.handler

  object BSONFields:

    val aiLevel           = "ai"
    val isOfferingDraw    = "od"
    val proposeTakebackAt = "ta"
    val rating            = "e"
    val ratingDiff        = "d"
    val provisional       = "p"
    val blursBits         = "l"
    val holdAlert         = "h"
    val berserk           = "be"
    val name              = "na"

  type UserId                = Option[User.ID]
  type Win                   = Option[Boolean]
  private[game] type Builder = Color => GamePlayerId => UserId => Win => Player

  private def safeRange(range: Range)(v: Int): Option[Int] = range.contains(v) option v
  private val ratingRange                                  = safeRange(0 to 4000)
  private val ratingDiffRange                              = safeRange(-1000 to 1000)

  import lila.db.dsl.*

  given playerReader: BSONDocumentReader[Builder] with
    import scala.util.{ Try, Success }
    def readDocument(doc: Bdoc): Try[Builder] = Success(builderRead(doc))

  def builderRead(doc: Bdoc): Builder = color =>
    id =>
      userId =>
        win =>
          import BSONFields.*
          Player(
            id = id,
            color = color,
            aiLevel = doc int aiLevel,
            isWinner = win,
            isOfferingDraw = doc booleanLike isOfferingDraw getOrElse false,
            proposeTakebackAt = doc int proposeTakebackAt getOrElse 0,
            userId = userId,
            rating = doc int rating flatMap ratingRange,
            ratingDiff = doc int ratingDiff flatMap ratingDiffRange,
            provisional = doc booleanLike provisional getOrElse false,
            blurs = doc.getAsOpt[Blurs](blursBits) getOrElse Blurs.zeroBlurs.zero,
            berserk = doc booleanLike berserk getOrElse false,
            name = doc string name
          )

  def playerWrite(p: Player) = {
    import BSONFields.*
    import Blurs.*
    $doc(
      aiLevel           -> p.aiLevel,
      isOfferingDraw    -> p.isOfferingDraw.option(true),
      proposeTakebackAt -> p.proposeTakebackAt.some.filter(_ > 0),
      rating            -> p.rating,
      ratingDiff        -> p.ratingDiff,
      provisional       -> p.provisional.option(true),
      blursBits         -> p.blurs.nonEmpty.??(p.blurs),
      name              -> p.name
    )
  }
