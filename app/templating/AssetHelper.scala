package lila.app
package templating

import play.api.mvc.RequestHeader

import lila.api.Context
import lila.app.ui.ScalatagsTemplate.{ *, given }
import lila.common.{ AssetVersion, ContentSecurityPolicy, Nonce }

trait AssetHelper extends HasEnv { self: I18nHelper with SecurityHelper =>

  private lazy val netDomain      = env.net.domain
  private lazy val assetDomain    = env.net.assetDomain
  private lazy val assetBaseUrl   = env.net.assetBaseUrl
  private lazy val socketDomains  = env.net.socketDomains
  private lazy val minifiedAssets = env.net.minifiedAssets
  lazy val vapidPublicKey         = env.push.vapidPublicKey

  lazy val picfitUrl = env.memo.picfitUrl

  lazy val sameAssetDomain = netDomain == assetDomain

  def assetVersion = AssetVersion.current

  def assetUrl(path: String): String       = s"$assetBaseUrl/assets/_$assetVersion/$path"
  def staticAssetUrl(path: String): String = s"$assetBaseUrl/assets/$path"

  def cdnUrl(path: String) = s"$assetBaseUrl$path"

  def cssTag(name: String)(using ctx: Context): Frag =
    cssTagWithDirAndTheme(name, isRTL(using ctx.lang), ctx.currentBg)

  def cssTagWithDirAndTheme(name: String, isRTL: Boolean, theme: String): Frag =
    cssAt(s"css/$name.${if (isRTL) "rtl" else "ltr"}.$theme.${if (minifiedAssets) "min" else "dev"}.css")

  def cssTagNoTheme(name: String): Frag =
    cssAt(s"css/$name.${if (minifiedAssets) "min" else "dev"}.css")

  private def cssAt(path: String): Frag =
    link(href := assetUrl(path), rel := "stylesheet")

  // load scripts in <head> and always use defer
  def jsAt(path: String): Frag = script(deferAttr, src := assetUrl(path))

  def jsTag(name: String): Frag = jsAt(s"javascripts/$name")

  def jsModule(name: String): Frag =
    jsAt(s"compiled/$name${minifiedAssets ?? ".min"}.js")

  def depsTag = jsAt("compiled/deps.min.js")

  def roundTag                         = jsModule("round")
  def roundNvuiTag(using ctx: Context) = ctx.blind option jsModule("round.nvui")

  def analyseTag                         = jsModule("analysisBoard")
  def analyseStudyTag                    = jsModule("analysisBoard.study")
  def analyseNvuiTag(using ctx: Context) = ctx.blind option jsModule("analysisBoard.nvui")

  def puzzleTag                         = jsModule("puzzle")
  def puzzleNvuiTag(using ctx: Context) = ctx.blind option jsModule("puzzle.nvui")

  def captchaTag          = jsModule("captcha")
  def infiniteScrollTag   = jsModule("infiniteScroll")
  def chessgroundTag      = jsAt("javascripts/vendor/chessground.min.js")
  def cashTag             = jsAt("javascripts/vendor/cash.min.js")
  def fingerprintTag      = jsAt("javascripts/fipr.js")
  def highchartsLatestTag = jsAt("vendor/highcharts-4.2.5/highcharts.js")
  def highchartsMoreTag   = jsAt("vendor/highcharts-4.2.5/highcharts-more.js")

  def prismicJs(using ctx: Context): Frag =
    raw {
      isGranted(_.Prismic) ?? {
        embedJsUnsafe("""window.prismic={endpoint:'https://lichess.prismic.io/api/v2'}""").render ++
          """<script src="//static.cdn.prismic.io/prismic.min.js"></script>"""
      }
    }

  def basicCsp(implicit req: RequestHeader): ContentSecurityPolicy =
    val sockets = socketDomains map { x => s"wss://$x${!req.secure ?? s" ws://$x"}" }
    // include both ws and wss when insecure because requests may come through a secure proxy
    val localDev = !req.secure ?? List("http://127.0.0.1:3000")
    ContentSecurityPolicy(
      defaultSrc = List("'self'", assetDomain),
      connectSrc =
        "'self'" :: assetDomain :: sockets ::: env.explorerEndpoint :: "https://utumno.backscattering.de" :: env.tablebaseEndpoint :: localDev,
      styleSrc = List("'self'", "'unsafe-inline'", assetDomain),
      frameSrc = List("'self'", assetDomain, "www.youtube.com", "player.twitch.tv"),
      workerSrc = List("'self'", assetDomain),
      imgSrc = List("data:", "*"),
      scriptSrc = List("'self'", assetDomain),
      fontSrc = List("'self'", assetDomain),
      baseUri = List("'none'")
    )

  def defaultCsp(using ctx: Context): ContentSecurityPolicy =
    val csp = basicCsp(ctx.req)
    ctx.nonce.fold(csp)(csp.withNonce(_))

  def analysisCsp(using ctx: Context): ContentSecurityPolicy =
    defaultCsp.withWebAssembly.withExternalEngine(env.externalEngineEndpoint)

  def embedJsUnsafe(js: String)(using ctx: Context): Frag =
    raw {
      val nonce = ctx.nonce ?? { nonce =>
        s""" nonce="$nonce""""
      }
      s"""<script$nonce>$js</script>"""
    }

  def embedJsUnsafe(js: String, nonce: Nonce): Frag =
    raw {
      s"""<script nonce="$nonce">$js</script>"""
    }

  def embedJsUnsafeLoadThen(js: String)(using ctx: Context): Frag =
    embedJsUnsafe(s"""lichess.load.then(()=>{$js})""")

  def embedJsUnsafeLoadThen(js: String, nonce: Nonce): Frag =
    embedJsUnsafe(s"""lichess.load.then(()=>{$js})""", nonce)
}
