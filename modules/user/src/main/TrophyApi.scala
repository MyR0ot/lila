package lila.user

import lila.db.dsl.{ *, given }
import lila.memo.*
import org.joda.time.DateTime
import reactivemongo.api.bson.*
import scala.concurrent.duration.*

final class TrophyApi(
    coll: Coll,
    kindColl: Coll,
    cacheApi: CacheApi
)(using ec: scala.concurrent.ExecutionContext):

  val kindCache =
    // careful of collisions with trophyKindStringBSONHandler
    val trophyKindObjectBSONHandler = Macros.handler[TrophyKind]

    cacheApi.sync[String, TrophyKind](
      name = "trophy.kind",
      initialCapacity = 32,
      compute = id => kindColl.byId[TrophyKind](id)(using trophyKindObjectBSONHandler).dmap(_ | TrophyKind.Unknown),
      default = _ => TrophyKind.Unknown,
      strategy = Syncache.WaitAfterUptime(20 millis),
      expireAfter = Syncache.ExpireAfterWrite(1 hour)
    )

  private given BSONHandler[TrophyKind] = BSONStringHandler.as[TrophyKind](kindCache.sync, _._id)

  private given BSONDocumentHandler[Trophy] = Macros.handler[Trophy]

  def findByUser(user: User, max: Int = 50): Fu[List[Trophy]] =
    coll.list[Trophy]($doc("user" -> user.id), max).map(_.filter(_.kind != TrophyKind.Unknown))

  def roleBasedTrophies(
      user: User,
      isPublicMod: Boolean,
      isDev: Boolean,
      isVerified: Boolean,
      isContentTeam: Boolean
  ): List[Trophy] =
    List(
      isPublicMod option Trophy(
        _id = "",
        user = user.id,
        kind = kindCache sync TrophyKind.moderator,
        date = org.joda.time.DateTime.now,
        url = none
      ),
      isDev option Trophy(
        _id = "",
        user = user.id,
        kind = kindCache sync TrophyKind.developer,
        date = org.joda.time.DateTime.now,
        url = none
      ),
      isVerified option Trophy(
        _id = "",
        user = user.id,
        kind = kindCache sync TrophyKind.verified,
        date = org.joda.time.DateTime.now,
        url = none
      ),
      isContentTeam option Trophy(
        _id = "",
        user = user.id,
        kind = kindCache sync TrophyKind.contentTeam,
        date = org.joda.time.DateTime.now,
        url = none
      )
    ).flatten

  def award(trophyUrl: String, userId: String, kindKey: String): Funit =
    coll.insert
      .one(
        $doc(
          "_id"  -> lila.common.ThreadLocalRandom.nextString(8),
          "user" -> userId,
          "kind" -> kindKey,
          "url"  -> trophyUrl,
          "date" -> DateTime.now
        )
      ) void
