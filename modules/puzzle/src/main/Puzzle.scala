package lila.puzzle

import cats.data.NonEmptyList
import chess.format.{ FEN, Forsyth, Uci }

import lila.rating.Glicko
import lila.common.Iso

case class Puzzle(
    id: PuzzleId,
    gameId: GameId,
    fen: FEN,
    line: NonEmptyList[Uci.Move],
    glicko: Glicko,
    plays: Int,
    vote: Float, // denormalized ratio of voteUp/voteDown
    themes: Set[PuzzleTheme.Key]
):
  // ply after "initial move" when we start solving
  def initialPly: Int =
    fen.fullMove ?? { fm =>
      fm * 2 - color.fold(1, 2)
    }

  def situationAfterInitialMove: Option[chess.Situation] = for {
    sit1 <- Forsyth << fen
    sit2 <- sit1.move(line.head).toOption.map(_.situationAfter)
  } yield sit2

  lazy val fenAfterInitialMove: FEN =
    situationAfterInitialMove map Forsyth.>> err s"Can't apply puzzle $id first move"

  def color = fen.color.fold[chess.Color](chess.White)(!_)

  def hasTheme(anyOf: PuzzleTheme*) = anyOf.exists(t => themes(t.key))

object Puzzle:

  val idSize = 5

  def toId(id: String) = id.size == idSize option PuzzleId(id)

  /* The mobile app requires numerical IDs.
   * We convert string ids from and to Longs using base 62
   */
  object numericalId:

    private val powers: List[Long] =
      (0 until idSize).toList.map(m => Math.pow(62, m).toLong)

    def apply(id: PuzzleId): Long = id.toList
      .zip(powers)
      .foldLeft(0L) { case (l, (char, pow)) =>
        l + charToInt(char) * pow
      }

    def apply(l: Long): Option[PuzzleId] = (l > 130_000) ?? {
      val str = powers.reverse
        .foldLeft(("", l)) { case ((id, rest), pow) =>
          val frac = rest / pow
          (s"${intToChar(frac.toInt)}$id", rest - frac * pow)
        }
        ._1
      (str.size == idSize) option PuzzleId(str)
    }

    private def charToInt(c: Char) =
      val i = c.toInt
      if (i > 96) i - 71
      else if (i > 64) i - 65
      else i + 4

    private def intToChar(i: Int): Char = {
      if (i < 26) i + 65
      else if (i < 52) i + 71
      else i - 4
    }.toChar

  case class UserResult(
      puzzleId: PuzzleId,
      userId: lila.user.User.ID,
      result: PuzzleResult,
      rating: (Int, Int)
  )

  object BSONFields:
    val id       = "_id"
    val gameId   = "gameId"
    val fen      = "fen"
    val line     = "line"
    val glicko   = "glicko"
    val vote     = "vote"
    val voteUp   = "vu"
    val voteDown = "vd"
    val plays    = "plays"
    val themes   = "themes"
    val opening  = "opening"
    val day      = "day"
    val issue    = "issue"
    val dirty    = "dirty" // themes need to be denormalized
    val tagMe    = "tagMe" // pending phase & opening

  given Iso.StringIso[PuzzleId] = Iso.opaque(PuzzleId.apply)
