package com.example.scalatarget

/** A trait whose `valueOf` returns a supertype of any enum, which an enum companion extends. */
trait WideLookup {
  def valueOf(s: String): AnyRef
}

/** A top-level enum whose companion extends [[WideLookup]]. */
enum WideTop {
  case Alpha, Beta
}

object WideTop extends WideLookup

/** An enum inside an object whose companion extends [[WideLookup]]. */
object WideObjectHost {
  enum WideIn {
    case Gamma, Delta
  }

  object WideIn extends WideLookup
}

/** An enum inside a class whose companion extends [[WideLookup]]. */
class WideClassHost {
  enum WideCls {
    case Epsilon, Zeta
  }

  object WideCls extends WideLookup
}

/** A local enum whose companion extends [[WideLookup]]. */
object WideLocalHost {
  def lookup(): AnyRef = {
    enum WideLoc {
      case Eta, Theta
    }
    object WideLoc extends WideLookup
    (WideLoc: WideLookup).valueOf("Eta")
  }
}

/** An enum whose companion extends [[WideLookup]] and declares a `valueOf` overload of its own. */
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

/** An enum with a parameterised case, for which scalac writes no `values` or `valueOf`, whose companion declares its own. */
enum WideShape {
  case Ring(r: Int)
  case Point
}

object WideShape {
  def valueOf(s: String): AnyRef = WideShape.Point
  def values: Array[WideShape] = Array(WideShape.Point)
}

/** An enum with only parameterised cases, for which scalac writes no `$new`, whose companion declares its own. */
enum WideOnly {
  case One(i: Int)
}

object WideOnly {
  def `$new`(i: Int, s: String): WideOnly = WideOnly.One(i)
}

/** An enum whose companion declares a polymorphic `valueOf` and a method the JVM knows as `valueOf`. */
enum WideTwin {
  case Left, Right
}

object WideTwin {
  def valueOf[T](s: String): T = null.asInstanceOf[T]

  @scala.annotation.targetName("valueOf")
  def lookupName(s: String): String = s
}

/** An enum whose companion extends [[WideLookup]] and declares a method the JVM knows as `valueOf`. */
enum WideNamed {
  case Mu, Nu
}

object WideNamed extends WideLookup {
  @scala.annotation.targetName("valueOf")
  def lookupName(s: String): String = s
}

/** A Java-compatible enum whose companion extends [[WideLookup]]. */
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

/** A generic trait declaring `valueOf`. */
trait WideOf[T] {
  def valueOf(s: String): T
}

/** An enum whose companion extends [[WideOf]] at a supertype of the enum, so scalac's `valueOf` returns it and has a bridge. */
enum WideGen {
  case Rho, Sigma
}

object WideGen extends WideOf[Product]

/** An enum whose companion extends [[WideLookup]] and declares a `valueOf` that erases to the enum type. */
enum WideBound {
  case Tau, Upsilon
}

object WideBound extends WideLookup {
  def valueOf[T <: WideBound](s: String): T = WideBound.Tau.asInstanceOf[T]
}

object WideLastUse {
  def use(): Int = WideGen.valueOf("Rho").hashCode + (WideBound: WideLookup).valueOf("Tau").hashCode + WideBound.valueOf[WideBound]("Tau").ordinal
}

/** A trait whose `valueOf` is polymorphic with an upper bound. */
trait WideBounded[U] {
  def valueOf[T <: U](s: String): T
}

/** An enum whose companion overrides [[WideBounded]]'s `valueOf`, which a bridge forwards to. */
enum WidePoly {
  case Phi, Chi
}

object WidePoly extends WideBounded[Product] {
  def valueOf[T <: Product](s: String): T = WidePoly.Phi.asInstanceOf[T]
}

/** A trait declaring a method the JVM knows as `valueOf`. */
trait WideRenamedLookup {
  @scala.annotation.targetName("valueOf")
  def find(s: String): AnyRef
}

/** An enum whose companion overrides [[WideRenamedLookup]]'s method the JVM knows as `valueOf`. */
enum WideRenamed {
  case Psi, Omega
}

object WideRenamed extends WideRenamedLookup {
  @scala.annotation.targetName("valueOf")
  def find(s: String): AnyRef = WideRenamed.Psi
}

/** A trait with a concrete polymorphic `valueOf`, which a class mixing it in forwards to. */
trait WideMixin {
  def valueOf[T](s: String): T = null.asInstanceOf[T]
}

/** An enum whose companion mixes in [[WideMixin]]. */
enum WideMixed {
  case First, Second
}

object WideMixed extends WideMixin

/** A trait whose `valueOf` returns `Product`. */
trait WideProductLookup {
  def valueOf(s: String): Product
}

/** A trait whose `valueOf` is polymorphic. */
trait WideAny {
  def valueOf[T](s: String): T
}

/** An enum whose companion extends [[WideProductLookup]] and overrides [[WideAny]]'s `valueOf`. */
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
