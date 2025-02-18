package lila.study

import org.joda.time.DateTime

import lila.user.User
import lila.common.Iso

case class Study(
    _id: StudyId,
    name: StudyName,
    members: StudyMembers,
    position: Position.Ref,
    ownerId: User.ID,
    visibility: Study.Visibility,
    settings: Settings,
    from: Study.From,
    likes: Study.Likes,
    description: Option[String] = None,
    topics: Option[StudyTopics] = None,
    createdAt: DateTime,
    updatedAt: DateTime
):

  import Study.*

  def id = _id

  def owner = members get ownerId

  def isOwner(id: User.ID) = ownerId == id

  def isMember(id: User.ID) = members contains id

  def canChat(id: User.ID) = Settings.UserSelection.allows(settings.chat, this, id.some)

  def canContribute(id: User.ID) =
    isOwner(id) || members.get(id).exists(_.canContribute) || id == User.lichessId

  def isCurrent(c: Chapter.Like) = c.id == position.chapterId

  def withChapter(c: Chapter.Like): Study = if (isCurrent(c)) this else rewindTo(c)

  def rewindTo(c: Chapter.Like): Study =
    copy(position = Position.Ref(chapterId = c.id, path = Path.root))

  def isPublic   = visibility == Study.Visibility.Public
  def isUnlisted = visibility == Study.Visibility.Unlisted
  def isPrivate  = visibility == Study.Visibility.Private

  def isNew = (nowSeconds - createdAt.getSeconds) < 4

  def isOld = (nowSeconds - updatedAt.getSeconds) > 20 * 60

  def cloneFor(user: User): Study =
    val owner = StudyMember(id = user.id, role = StudyMember.Role.Write)
    copy(
      _id = Study.makeId,
      members = StudyMembers(Map(user.id -> owner)),
      ownerId = owner.id,
      visibility = Study.Visibility.Private,
      from = Study.From.Study(id),
      likes = Likes(1),
      createdAt = DateTime.now,
      updatedAt = DateTime.now
    )

  def nbMembers = members.members.size

  def withoutMembers = copy(members = StudyMembers.empty)

  def light = LightStudy(isPublic, members.contributorIds)

  def topicsOrEmpty = topics | StudyTopics.empty

  def addTopics(ts: StudyTopics) =
    copy(
      topics = topics.fold(ts)(_ ++ ts).some
    )

object Study:

  val maxChapters = 64

  val previewNbMembers  = 4
  val previewNbChapters = 4

  given Iso.StringIso[StudyName] = Iso.opaque(StudyName.apply)

  case class IdName(_id: StudyId, name: StudyName):
    def id = _id

  def toName(str: String) = StudyName(lila.common.String.fullCleanUp(str) take 100)

  sealed trait Visibility:
    lazy val key = Visibility.this.toString.toLowerCase
  object Visibility:
    case object Private  extends Visibility
    case object Unlisted extends Visibility
    case object Public   extends Visibility
    val byKey = List(Private, Unlisted, Public).map { v =>
      v.key -> v
    }.toMap

  case class Likes(value: Int) extends AnyVal
  case class Liking(likes: Likes, me: Boolean)
  val emptyLiking = Liking(Likes(0), me = false)

  case class Rank(value: DateTime) extends AnyVal
  object Rank:
    def compute(likes: Likes, createdAt: DateTime) =
      Rank {
        createdAt plusHours likesToHours(likes)
      }
    private def likesToHours(likes: Likes): Int =
      if (likes.value < 1) 0
      else (5 * math.log(likes.value) + 1).toInt.min(likes.value) * 24

  sealed trait From
  object From:
    case object Scratch                           extends From
    case class Game(id: String)                   extends From
    case class Study(id: StudyId)                 extends From
    case class Relay(clonedFrom: Option[StudyId]) extends From

  case class Data(
      name: String,
      visibility: String,
      computer: Settings.UserSelection,
      explorer: Settings.UserSelection,
      cloneable: Settings.UserSelection,
      shareable: Settings.UserSelection,
      chat: Settings.UserSelection,
      sticky: String,
      description: String
  ):
    def vis = Visibility.byKey.getOrElse(visibility, Visibility.Public)
    def settings =
      Settings(computer, explorer, cloneable, shareable, chat, sticky == "true", description == "true")

  case class WithChapter(study: Study, chapter: Chapter)

  case class WithChapters(study: Study, chapters: Seq[StudyChapterName])

  case class WithActualChapters(study: Study, chapters: Seq[Chapter])

  case class WithChaptersAndLiked(study: Study, chapters: Seq[StudyChapterName], liked: Boolean)

  case class WithLiked(study: Study, liked: Boolean)

  case class LightStudy(isPublic: Boolean, contributors: Set[User.ID])

  val idSize = 8

  def makeId = StudyId(lila.common.ThreadLocalRandom nextString idSize)

  def make(
      user: User,
      from: From,
      id: Option[StudyId] = None,
      name: Option[StudyName] = None,
      settings: Option[Settings] = None
  ) =
    val owner = StudyMember(id = user.id, role = StudyMember.Role.Write)
    Study(
      _id = id | makeId,
      name = name | StudyName(s"${user.username}'s Study"),
      members = StudyMembers(Map(user.id -> owner)),
      position = Position.Ref(StudyChapterId(""), Path.root),
      ownerId = user.id,
      visibility = Visibility.Public,
      settings = settings | Settings.init,
      from = from,
      likes = Likes(1),
      createdAt = DateTime.now,
      updatedAt = DateTime.now
    )
