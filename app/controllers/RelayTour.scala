package controllers

import play.api.data.Form
import play.api.mvc.*
import scala.annotation.nowarn
import scala.concurrent.duration.*
import scala.util.chaining.*
import views.*

import lila.api.Context
import lila.app.{ given, * }
import lila.common.config.MaxPerSecond
import lila.common.{ HTTPRequest, IpAddress }
import lila.relay.{ RelayRound as RoundModel, RelayTour as TourModel }
import lila.user.{ User as UserModel }
import lila.common.config

final class RelayTour(env: Env, apiC: => Api, prismicC: => Prismic) extends LilaController(env):

  def index(page: Int) =
    Open { implicit ctx =>
      Reasonable(page, config.Max(20)) {
        for {
          active <- (page == 1).??(env.relay.api.officialActive.get({}))
          pager  <- env.relay.pager.inactive(page)
        } yield Ok(html.relay.tour.index(active, pager))
      }
    }

  def calendar = page("broadcast-calendar", "calendar")
  def help     = page("broadcasts", "help")

  private def page(bookmark: String, menu: String) =
    Open { implicit ctx =>
      pageHit
      OptionOk(prismicC getBookmark bookmark) { case (doc, resolver) =>
        html.relay.tour.page(doc, resolver, menu)
      }
    }

  def form = Auth { implicit ctx => me =>
    NoLameOrBot {
      Ok(html.relay.tourForm.create(env.relay.tourForm.create)).toFuccess
    }
  }

  def create =
    AuthOrScopedBody(_.Study.Write)(
      auth = implicit ctx =>
        me =>
          NoLameOrBot {
            env.relay.tourForm.create
              .bindFromRequest()(ctx.body, formBinding)
              .fold(
                err => BadRequest(html.relay.tourForm.create(err)).toFuccess,
                setup =>
                  rateLimitCreation(me, ctx.req, Redirect(routes.RelayTour.index())) {
                    env.relay.api.tourCreate(setup, me) map { tour =>
                      Redirect(routes.RelayRound.form(tour.id.value)).flashSuccess
                    }
                  }
              )
          },
      scoped = req =>
        me =>
          NoLameOrBot(me) {
            env.relay.tourForm.create
              .bindFromRequest()(req, formBinding)
              .fold(
                err => BadRequest(apiFormError(err)).toFuccess,
                setup =>
                  rateLimitCreation(me, req, rateLimited) {
                    JsonOk {
                      env.relay.api.tourCreate(setup, me) map { tour =>
                        env.relay.jsonView(tour.withRounds(Nil), withUrls = true)
                      }
                    }
                  }
              )
          }
    )

  def edit(id: String) = Auth { implicit ctx => me =>
    WithTourCanUpdate(id) { tour =>
      Ok(html.relay.tourForm.edit(tour, env.relay.tourForm.edit(tour))).toFuccess
    }
  }

  def update(id: String) =
    AuthOrScopedBody(_.Study.Write)(
      auth = implicit ctx =>
        me =>
          WithTourCanUpdate(id) { tour =>
            env.relay.tourForm
              .edit(tour)
              .bindFromRequest()(ctx.body, formBinding)
              .fold(
                err => BadRequest(html.relay.tourForm.edit(tour, err)).toFuccess,
                setup =>
                  env.relay.api.tourUpdate(tour, setup, me) inject
                    Redirect(routes.RelayTour.redirectOrApiTour(tour.slug, tour.id.value))
              )
          },
      scoped = implicit req =>
        me =>
          env.relay.api tourById TourModel.Id(id) flatMap {
            _ ?? { tour =>
              env.relay.api.canUpdate(me, tour) flatMap {
                _ ?? env.relay.tourForm
                  .edit(tour)
                  .bindFromRequest()
                  .fold(
                    err => BadRequest(apiFormError(err)).toFuccess,
                    setup => env.relay.api.tourUpdate(tour, setup, me) inject jsonOkResult
                  )
              }
            }
          }
    )

  def redirectOrApiTour(@nowarn("msg=unused") slug: String, anyId: String) = Open { implicit ctx =>
    env.relay.api byIdWithTour RoundModel.Id(anyId) flatMap {
      case Some(rt) => Redirect(rt.path).toFuccess // BC old broadcast URLs
      case None =>
        env.relay.api tourById TourModel.Id(anyId) flatMap {
          _ ?? { tour =>
            render.async {
              case Accepts.Json() =>
                JsonOk {
                  env.relay.api.withRounds(tour) map { trs =>
                    env.relay.jsonView(trs, withUrls = true)
                  }
                }
              case _ => redirectToTour(tour)
            }
          }
        }
    }
  }

  def pgn(id: String) =
    Action.async { req =>
      env.relay.api tourById TourModel.Id(id) map {
        _ ?? { tour =>
          apiC.GlobalConcurrencyLimitPerIP(HTTPRequest ipAddress req)(
            env.relay.pgnStream.exportFullTour(tour)
          ) { source =>
            asAttachmentStream(s"${env.relay.pgnStream filename tour}.pgn")(
              Ok chunked source as pgnContentType
            )
          }
        }
      }
    }

  def apiIndex =
    Action.async { implicit req =>
      apiC.jsonStream {
        env.relay.api
          .officialTourStream(MaxPerSecond(20), getInt("nb", req) | 20)
          .map(env.relay.jsonView.apply(_, withUrls = true))
      }.toFuccess
    }

  private def redirectToTour(tour: TourModel)(implicit ctx: Context): Fu[Result] =
    env.relay.api.activeTourNextRound(tour) orElse env.relay.api.tourLastRound(tour) flatMap {
      case None =>
        ctx.me.?? { env.relay.api.canUpdate(_, tour) } flatMap {
          _ ?? Redirect(routes.RelayRound.form(tour.id.value)).toFuccess
        }
      case Some(round) => Redirect(round.withTour(tour).path).toFuccess
    }

  private def WithTour(id: String)(
      f: TourModel => Fu[Result]
  )(implicit ctx: Context): Fu[Result] =
    OptionFuResult(env.relay.api tourById TourModel.Id(id))(f)

  private def WithTourCanUpdate(id: String)(
      f: TourModel => Fu[Result]
  )(implicit ctx: Context): Fu[Result] =
    WithTour(id) { tour =>
      ctx.me.?? { env.relay.api.canUpdate(_, tour) } flatMap {
        _ ?? f(tour)
      }
    }

  private val CreateLimitPerUser = new lila.memo.RateLimit[lila.user.User.ID](
    credits = 10 * 10,
    duration = 24.hour,
    key = "broadcast.tournament.user"
  )

  private val CreateLimitPerIP = new lila.memo.RateLimit[IpAddress](
    credits = 10 * 10,
    duration = 24.hour,
    key = "broadcast.tournament.ip"
  )

  private[controllers] def rateLimitCreation(
      me: UserModel,
      req: RequestHeader,
      fail: => Result
  )(
      create: => Fu[Result]
  ): Fu[Result] =
    val cost =
      if (isGranted(_.Relay, me)) 2
      else if (me.hasTitle || me.isVerified) 5
      else 10
    CreateLimitPerUser(me.id, cost = cost) {
      CreateLimitPerIP(HTTPRequest ipAddress req, cost = cost) {
        create
      }(fail.toFuccess)
    }(fail.toFuccess)
