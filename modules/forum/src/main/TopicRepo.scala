package lila.forum

import org.joda.time.DateTime
import Filter.*
import lila.db.dsl.{ *, given }
import lila.user.User
import scala.concurrent.duration.*

final class TopicRepo(val coll: Coll, filter: Filter = Safe)(using
    ec: scala.concurrent.ExecutionContext
):

  import BSONHandlers.given

  def forUser(user: Option[User]) =
    withFilter(user.filter(_.marks.troll).fold[Filter](Safe) { u =>
      SafeAnd(u.id)
    })
  def withFilter(f: Filter) = if (f == filter) this else new TopicRepo(coll, f)
  def unsafe                = withFilter(Unsafe)

  private val noTroll = $doc("troll" -> false)
  private val trollFilter = filter match
    case Safe       => noTroll
    case SafeAnd(u) => $or(noTroll, $doc("userId" -> u))
    case Unsafe     => $empty

  private lazy val notStickyQuery = $doc("sticky" $ne true)
  private lazy val stickyQuery    = $doc("sticky" -> true)

  def byId(id: Topic.ID): Fu[Option[Topic]] = coll.byId[Topic](id)

  def close(id: String, value: Boolean): Funit =
    coll.updateField($id(id), "closed", value).void

  def remove(topic: Topic): Funit =
    coll.delete.one($id(topic.id)).void

  def sticky(id: String, value: Boolean): Funit =
    coll.updateField($id(id), "sticky", value).void

  def byCateg(categ: Categ): Fu[List[Topic]] =
    coll.list[Topic](byCategQuery(categ))

  def countByCateg(categ: Categ): Fu[Int] =
    coll.countSel(byCategQuery(categ))

  def byTree(categSlug: String, slug: String): Fu[Option[Topic]] =
    coll.one[Topic]($doc("categId" -> categSlug, "slug" -> slug) ++ trollFilter)

  def existsByTree(categSlug: String, slug: String): Fu[Boolean] =
    coll.exists($doc("categId" -> categSlug, "slug" -> slug))

  def stickyByCateg(categ: Categ): Fu[List[Topic]] =
    coll.list[Topic](byCategQuery(categ) ++ stickyQuery)

  def nextSlug(categ: Categ, name: String, it: Int = 1): Fu[String] =
    val slug = Topic.nameToId(name) + ~(it != 1).option("-" + it)
    // also take troll topic into accounts
    unsafe.byTree(categ.slug, slug) flatMap { found =>
      if (found.isDefined) nextSlug(categ, name, it + 1)
      else fuccess(slug)
    }

  def byCategQuery(categ: Categ)          = $doc("categId" -> categ.slug) ++ trollFilter
  def byCategNotStickyQuery(categ: Categ) = byCategQuery(categ) ++ notStickyQuery
