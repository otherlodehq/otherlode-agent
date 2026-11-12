package com.example.scalatarget

trait WideLookup {
  def valueOf(s: String): AnyRef
}

enum WideTop {
  case Alpha, Beta
}

object WideTop extends WideLookup

object WideObjectHost {
  enum WideIn {
    case Gamma, Delta
  }

  object WideIn extends WideLookup
}

class WideClassHost {
  enum WideCls {
    case Epsilon, Zeta
  }

  object WideCls extends WideLookup
}

object WideLocalHost {
  def lookup(): AnyRef = {
    enum WideLoc {
      case Eta, Theta
    }
    object WideLoc extends WideLookup
    (WideLoc: WideLookup).valueOf("Eta")
  }
}

enum WideOver {
  case Iota, Kappa
}

object WideOver extends WideLookup {
  def valueOf(s: String, n: Int): AnyRef = WideOver.valueOf(s)
}

object WideUse {
  def use(): Int = {
    val all = List[WideLookup](WideTop, WideObjectHost.WideIn, new WideClassHost().WideCls, WideOver)
    all.map(_.valueOf("x").hashCode).sum + WideLocalHost.lookup().hashCode + WideOver.valueOf("Iota", 1).hashCode
  }
}

enum WideShape {
  case Ring(r: Int)
  case Point
}

object WideShape {
  def valueOf(s: String): AnyRef = WideShape.Point
  def values: Array[WideShape] = Array(WideShape.Point)
}

enum WideOnly {
  case One(i: Int)
}

object WideOnly {
  def `$new`(i: Int, s: String): WideOnly = WideOnly.One(i)
}

enum WideTwin {
  case Left, Right
}

object WideTwin {
  def valueOf[T](s: String): T = null.asInstanceOf[T]

  @scala.annotation.targetName("valueOf")
  def lookupName(s: String): String = s
}

enum WideNamed {
  case Mu, Nu
}

object WideNamed extends WideLookup {
  @scala.annotation.targetName("valueOf")
  def lookupName(s: String): String = s
}

enum WideJava extends java.lang.Enum[WideJava] {
  case Xi, Omicron
}

object WideJava extends WideLookup

object WideMoreUse {
  def use(): Int =
    WideShape.valueOf("Point").hashCode + WideShape.values.length + WideOnly.`$new`(1, "One").ordinal +
      WideTwin.valueOf[String]("Left").length + WideTwin.lookupName("Right").length + WideNamed.lookupName("Mu").length +
      List[WideLookup](WideNamed, WideJava).map(_.valueOf("x").hashCode).sum
}

trait WideOf[T] {
  def valueOf(s: String): T
}

enum WideGen {
  case Rho, Sigma
}

object WideGen extends WideOf[Product]

enum WideBound {
  case Tau, Upsilon
}

object WideBound extends WideLookup {
  def valueOf[T <: WideBound](s: String): T = WideBound.Tau.asInstanceOf[T]
}

object WideLastUse {
  def use(): Int = WideGen.valueOf("Rho").hashCode + (WideBound: WideLookup).valueOf("Tau").hashCode + WideBound.valueOf[WideBound]("Tau").ordinal
}

trait WideBounded[U] {
  def valueOf[T <: U](s: String): T
}

enum WidePoly {
  case Phi, Chi
}

object WidePoly extends WideBounded[Product] {
  def valueOf[T <: Product](s: String): T = WidePoly.Phi.asInstanceOf[T]
}

trait WideRenamedLookup {
  @scala.annotation.targetName("valueOf")
  def find(s: String): AnyRef
}

enum WideRenamed {
  case Psi, Omega
}

object WideRenamed extends WideRenamedLookup {
  @scala.annotation.targetName("valueOf")
  def find(s: String): AnyRef = WideRenamed.Psi
}

trait WideMixin {
  def valueOf[T](s: String): T = null.asInstanceOf[T]
}

enum WideMixed {
  case First, Second
}

object WideMixed extends WideMixin

trait WideProductLookup {
  def valueOf(s: String): Product
}

trait WideAny {
  def valueOf[T](s: String): T
}

enum WideBoth {
  case Third, Fourth
}

object WideBoth extends WideProductLookup with WideAny {
  def valueOf[T](s: String): T = null.asInstanceOf[T]
}

object WidePolyUse {
  def use(): Int =
    WidePoly.valueOf[Product]("Phi").hashCode + WideRenamed.find("Psi").hashCode + WideMixed.valueOf[AnyRef]("First").hashCode +
      (WideBoth: WideProductLookup).valueOf("Third").hashCode + WideBoth.valueOf[AnyRef]("Fourth").hashCode
}
