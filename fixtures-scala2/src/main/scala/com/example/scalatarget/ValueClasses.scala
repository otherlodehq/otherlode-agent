package com.example.scalatarget

object ValueSyntax {
  implicit class RichInt(private val x: Int) extends AnyVal {
    def doubled: Int = x * 2
  }
}

class Weight(val grams: Long) extends AnyVal {
  def scaled(factor: Double, offset: Long): Double = grams * factor + offset
  def plus(n: Int): Long = grams + n
  def plus(n: Long): Long = grams + n
}

class Flag(val on: Boolean) extends AnyVal {
  def flipped: Boolean = !on
}

class ByteBox(val b: Byte) extends AnyVal
class ShortBox(val s: Short) extends AnyVal
class CharBox(val c: Char) extends AnyVal
class FloatBox(val f: Float) extends AnyVal
class DoubleBox(val d: Double) extends AnyVal
class TextBox(val t: String) extends AnyVal
class GenericBox[T](val v: T) extends AnyVal
class ArrayBox(val a: Array[Int]) extends AnyVal

final case class Meters(value: Double) extends AnyVal

final case class Label(text: String) extends AnyVal {
  def shout(suffix: String): String = text + suffix
  override def toString: String = text
}

final class Almost(val n: Int) {
  def twice: Int = Almost.twice$extension(n) + 1
}

object Almost {
  def twice$extension(n: Int): Int = n * 2
  def hashCode$extension(n: Int): Int = 7
}

object ValueDriver {
  def doubledUnboxed(): Int = {
    import ValueSyntax._
    21.doubled
  }

  def scaledUnboxed(): Double = new Weight(3L).scaled(2.0, 1L)

  def copiedUnboxed(): Double = Meters(1.5).copy(2.5).value
}
