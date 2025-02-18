package lila

object Lila extends Lila:

  def nowNanos: Long  = System.nanoTime()
  def nowMillis: Long = System.currentTimeMillis()
  def nowCentis: Long = nowMillis / 10
  def nowTenths: Long = nowMillis / 100
  def nowSeconds: Int = (nowMillis / 1000).toInt

  object makeTimeout:

    import akka.util.Timeout
    import scala.concurrent.duration.*

    given short: Timeout  = seconds(1)
    given large: Timeout  = seconds(5)
    given larger: Timeout = seconds(30)

    def apply(duration: FiniteDuration) = Timeout(duration)
    def millis(s: Int): Timeout         = Timeout(s.millis)
    def seconds(s: Int): Timeout        = Timeout(s.seconds)
    def minutes(m: Int): Timeout        = Timeout(m.minutes)

  def some[A](a: A): Option[A] = Some(a)

trait Lila
    extends lila.base.LilaTypes
    with lila.base.NewTypes
    with lila.base.LilaModel
    with cats.syntax.OptionSyntax
    with cats.syntax.ListSyntax
    with ornicar.scalalib.Zeros
    with lila.base.LilaLibraryExtensions:

  export ornicar.scalalib.OrnicarBooleanWrapper

  trait IntValue extends Any:
    def value: Int
    override def toString = value.toString
  trait BooleanValue extends Any:
    def value: Boolean
    override def toString = value.toString
  trait DoubleValue extends Any:
    def value: Double
    override def toString = value.toString
  trait StringValue extends Any:
    def value: String
    override def toString = value
  trait Percent extends Any:
    def value: Double
    def toInt = Math.round(value).toInt // round to closest

  // replaces Product.unapply in play forms
  def unapply[P <: Product](p: P)(using m: scala.deriving.Mirror.ProductOf[P]): Option[m.MirroredElemTypes] =
    Some(Tuple.fromProductTyped(p))

  import play.api.libs.json.{ JsObject, JsValue }
  import lila.base.{ LilaJsObject, LilaJsValue }
  // can't use extensions because of method name shadowing :(
  implicit def toLilaJsObject(jo: JsObject): LilaJsObject = new LilaJsObject(jo)
  implicit def toLilaJsValue(jv: JsValue): LilaJsValue    = new LilaJsValue(jv)
