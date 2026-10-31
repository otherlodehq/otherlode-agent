package com.example.scalatarget

object ValueSyntax {
  implicit class RichInt(private val x: Int) extends AnyVal {
    def doubled: Int = x * 2
  }
}

/** A value class with a public `val` and a method with arguments and a non-int result. */
class Weight(val grams: Long) extends AnyVal {
  def scaled(factor: Double, offset: Long): Double = grams * factor + offset
  def plus(n: Int): Long = grams + n
  def plus(n: Long): Long = grams + n
}

/** A value class over a `boolean`. */
class Flag(val on: Boolean) extends AnyVal {
  def flipped: Boolean = !on
}

/** One value class over each other primitive, a `String`, a type parameter and an array. */
class ByteBox(val b: Byte) extends AnyVal
class ShortBox(val s: Short) extends AnyVal
class CharBox(val c: Char) extends AnyVal
class FloatBox(val f: Float) extends AnyVal
class DoubleBox(val d: Double) extends AnyVal
class TextBox(val t: String) extends AnyVal
class GenericBox[T](val v: T) extends AnyVal
class ArrayBox(val a: Array[Int]) extends AnyVal

/** A case value class over a primitive. */
final case class Meters(value: Double) extends AnyVal

/** A case value class over a `String`, with a method of its own and a `toString` of its own. */
final case class Label(text: String) extends AnyVal {
  def shout(suffix: String): String = text + suffix
  override def toString: String = text
}

/**
 * A final class with one field whose companion declares `twice$extension`, as a value class's does,
 * with bodies that are not scalac's.
 */
final class Almost(val n: Int) {
  def twice: Int = Almost.twice$extension(n) + 1
}

object Almost {
  def twice$extension(n: Int): Int = n * 2
  def hashCode$extension(n: Int): Int = 7
}

/** Calls the value classes above in the unboxed form, so no boxed method runs. */
object ValueDriver {
  def doubledUnboxed(): Int = {
    import ValueSyntax._
    21.doubled
  }

  def scaledUnboxed(): Double = new Weight(3L).scaled(2.0, 1L)

  def copiedUnboxed(): Double = Meters(1.5).copy(2.5).value
}
