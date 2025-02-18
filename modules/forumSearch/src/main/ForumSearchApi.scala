package lila.forumSearch

import akka.stream.scaladsl.*
import play.api.libs.json.*

import lila.common.Json.given
import lila.forum.{ Post, PostApi, PostLiteView, PostRepo, PostView }
import lila.search.*

final class ForumSearchApi(
    client: ESClient,
    postApi: PostApi,
    postRepo: PostRepo
)(using
    ec: scala.concurrent.ExecutionContext,
    mat: akka.stream.Materializer
) extends SearchReadApi[PostView, Query]:

  def search(query: Query, from: From, size: Size) =
    client.search(query, from, size) flatMap { res =>
      postApi.viewsFromIds(res.ids)
    }

  def count(query: Query) =
    client.count(query) dmap (_.count)

  def store(post: Post) =
    postApi liteView post flatMap {
      _ ?? { view =>
        client.store(Id(view.post.id), toDoc(view))
      }
    }

  private def toDoc(view: PostLiteView) =
    val author = ~(view.post.userId orElse view.post.author map (_.toLowerCase))
    Json.obj(
      Fields.body    -> view.post.text.take(10000),
      Fields.topic   -> view.topic.name,
      Fields.author  -> author,
      Fields.topicId -> view.topic.id,
      Fields.troll   -> view.post.troll,
      Fields.date    -> view.post.createdAt
    )

  def reset =
    client match
      case c: ESClientHttp =>
        c.putMapping >> {
          postRepo.nonGhostCursor
            .documentSource()
            .via(lila.common.LilaStream.logRate[Post]("forum index")(logger))
            .grouped(200)
            .mapAsync(1)(postApi.liteViews)
            .map(_.map(v => Id(v.post.id) -> toDoc(v)))
            .mapAsyncUnordered(2)(c.storeBulk)
            .toMat(Sink.ignore)(Keep.right)
            .run()
        } >> client.refresh

      case _ => funit
