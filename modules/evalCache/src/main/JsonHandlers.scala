package lila.evalCache

import cats.implicits.*
import chess.format.{ FEN, Uci }
import play.api.libs.json.*

import lila.common.Json.{ *, given }
import lila.evalCache.EvalCacheEntry.*
import lila.tree.Eval.*

object JsonHandlers:

  private given Writes[Cp]     = writeAs(_.value)
  private given Writes[Mate]   = writeAs(_.value)
  private given Writes[Knodes] = writeAs(_.value)

  def writeEval(e: Eval, fen: FEN) =
    Json.obj(
      "fen"    -> fen,
      "knodes" -> e.knodes,
      "depth"  -> e.depth,
      "pvs"    -> e.pvs.toList.map(writePv)
    )

  private def writePv(pv: Pv) =
    Json
      .obj(
        "moves" -> pv.moves.value.toList.map(_.uci).mkString(" ")
      )
      .add("cp", pv.score.cp)
      .add("mate", pv.score.mate)

  private[evalCache] def readPut(trustedUser: TrustedUser, o: JsObject): Option[Input.Candidate] =
    o obj "d" flatMap { readPutData(trustedUser, _) }

  private[evalCache] def readPutData(trustedUser: TrustedUser, d: JsObject): Option[Input.Candidate] =
    for {
      fen    <- d str "fen"
      knodes <- d int "knodes"
      depth  <- d int "depth"
      pvObjs <- d objs "pvs"
      pvs    <- pvObjs.map(parsePv).sequence.flatMap(_.toNel)
      variant = chess.variant.Variant orDefault ~d.str("variant")
    } yield Input.Candidate(
      variant,
      fen,
      Eval(
        pvs = pvs,
        knodes = Knodes(knodes),
        depth = depth,
        by = trustedUser.user.id,
        trust = trustedUser.trust
      )
    )

  private def parsePv(d: JsObject): Option[Pv] =
    for {
      movesStr <- d str "moves"
      moves <-
        movesStr
          .split(' ')
          .take(EvalCacheEntry.MAX_PV_SIZE)
          .foldLeft(List.empty[Uci].some) {
            case (Some(ucis), str) => Uci(str) map (_ :: ucis)
            case _                 => None
          }
          .flatMap(_.reverse.toNel) map Moves.apply
      cp   = d int "cp" map Cp.apply
      mate = d int "mate" map Mate.apply
      score <- cp.map(Score.cp) orElse mate.map(Score.mate)
    } yield Pv(score, moves)
