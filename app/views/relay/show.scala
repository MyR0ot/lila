package views.html
package relay

import play.api.libs.json.Json

import lila.api.{ Context, given }
import lila.app.templating.Environment.{ given, * }
import lila.app.ui.ScalatagsTemplate.{ *, given }
import lila.common.String.html.safeJsonValue
import lila.socket.SocketVersion
import lila.socket.SocketVersion.given

import controllers.routes

object show:

  def apply(
      rt: lila.relay.RelayRound.WithTourAndStudy,
      data: lila.relay.JsonView.JsData,
      chatOption: Option[lila.chat.UserChat.Mine],
      socketVersion: SocketVersion,
      streamers: List[lila.user.User.ID]
  )(implicit ctx: Context) =
    views.html.base.layout(
      title = rt.fullName,
      moreCss = cssTag("analyse.relay"),
      moreJs = frag(
        analyseStudyTag,
        analyseNvuiTag,
        embedJsUnsafe(s"""lichess.relay=${safeJsonValue(
            Json.obj(
              "relay"    -> data.relay,
              "study"    -> data.study.add("admin" -> isGranted(_.StudyAdmin)),
              "data"     -> data.analysis,
              "i18n"     -> bits.jsI18n,
              "tagTypes" -> lila.study.PgnTags.typesToString,
              "userId"   -> ctx.userId,
              "chat" -> chatOption.map(c =>
                chat.json(
                  c.chat,
                  name = trans.chatRoom.txt(),
                  timeout = c.timeout,
                  writeable = ctx.userId.??(rt.study.canChat),
                  public = true,
                  resourceId = lila.chat.Chat.ResourceId(s"relay/${c.chat.id}"),
                  localMod = rt.tour.tier.isEmpty && ctx.userId.??(rt.study.canContribute),
                  broadcastMod = rt.tour.tier.isDefined && isGranted(_.BroadcastTimeout)
                )
              ),
              "socketUrl"     -> views.html.study.show.socketUrl(rt.study.id),
              "socketVersion" -> socketVersion
            ) ++ views.html.board.bits.explorerAndCevalConfig
          )}""")
      ),
      chessground = false,
      zoomable = true,
      csp = analysisCsp.withWikiBooks.some,
      openGraph = lila.app.ui
        .OpenGraph(
          title = rt.fullName,
          url = s"$netBaseUrl${rt.path}",
          description = shorten(rt.tour.description, 152)
        )
        .some
    )(
      frag(
        main(cls := "analyse"),
        views.html.study.bits.streamers(streamers)
      )
    )
