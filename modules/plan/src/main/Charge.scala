package lila.plan

import org.joda.time.DateTime
import cats.implicits.*

import lila.user.User

case class Charge(
    _id: String, // random
    userId: Option[User.ID],
    giftTo: Option[User.ID] = none,
    stripe: Option[Charge.Stripe] = none,
    payPal: Option[Charge.PayPalLegacy] = none,
    payPalCheckout: Option[Charge.PayPalCheckout] = none,
    money: Money,
    usd: Usd,
    date: DateTime
):

  def id = _id

  def isPayPalLegacy   = payPal.nonEmpty
  def isPayPalCheckout = payPalCheckout.nonEmpty
  def isStripe         = stripe.nonEmpty

  def serviceName =
    if (isStripe) "stripe"
    else if (isPayPalLegacy) "paypal legacy"
    else if (isPayPalCheckout) "paypal checkout"
    else "???"

  def toGift = (userId, giftTo) mapN { Charge.Gift(_, _, date) }

object Charge:

  def make(
      userId: Option[User.ID],
      giftTo: Option[User.ID],
      stripe: Option[Charge.Stripe] = none,
      payPal: Option[Charge.PayPalLegacy] = none,
      payPalCheckout: Option[Charge.PayPalCheckout] = none,
      money: Money,
      usd: Usd
  ) =
    Charge(
      _id = lila.common.ThreadLocalRandom nextString 8,
      userId = userId,
      giftTo = giftTo,
      stripe = stripe,
      payPal = payPal,
      payPalCheckout = payPalCheckout,
      money = money,
      usd = usd,
      date = DateTime.now
    )

  case class Stripe(
      chargeId: StripeChargeId,
      customerId: StripeCustomerId
  )

  case class PayPalLegacy(
      ip: Option[String],
      name: Option[String],
      email: Option[String],
      txnId: Option[String],
      subId: Option[String]
  )

  case class PayPalCheckout(
      orderId: PayPalOrderId,
      payerId: PayPalPayerId,
      subscriptionId: Option[PayPalSubscriptionId]
  )

  case class Gift(from: User.ID, to: User.ID, date: DateTime)
