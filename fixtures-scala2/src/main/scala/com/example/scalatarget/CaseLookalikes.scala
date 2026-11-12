package com.example.scalatarget

case class Aux(a: Int) {
  def this(s: String) = this(s.toInt)
  def copy(s: String): Aux = new Aux(s)
}

object Aux {
  def apply(s: String): Aux = new Aux(s)
}

abstract class Base(val a: Int)

case class Q(override val a: Int)(val b: Int) extends Base(a)

case class Lie(a: Int) {
  override def productArity: Int = 3
}

case class Al(a: Int, b: String) {
  def _1: Int = a
}

case class E3(a: Int, b: String) {
  override def equals(o: Any): Boolean = (this eq o.asInstanceOf[AnyRef]) || (o match {
    case that: E3 => a == that.a && that.canEqual(this)
    case _ => false
  })
}

case class P3(x: Double, y: Double, z: Double)

case class Lie2(a: Int, b: Int) {
  override def productArity: Int = 1
}

case class Node(a: Int) {
  val child: Node = if (a > 0) new Node(a - 1) else null
}

case class TryBody(a: Int) {
  try { println(a) }
  catch { case _: Exception => () }
}

final case class FT(a: Int, b: String)

final case class FE()

final case class F1(s: String)

class FinalOuter {
  final case class FIn(a: Int, b: String)

  final case class FInEmpty()
}

object FinalHolder {
  final case class FObj(a: Int)
}

case class NoCanEqual(a: Int, b: String) {
  override def equals(o: Any): Boolean = (this eq o.asInstanceOf[AnyRef]) || (o match {
    case that: NoCanEqual => a == that.a && b == that.b
    case _ => false
  })
}

final case class UC(a: Int, b: String) {
  override def canEqual(o: Any): Boolean = o.isInstanceOf[UC]
}

final case class UC2(a: Int, b: String) {
  override def canEqual(o: Any): Boolean = false
  override def equals(x$1: Any): Boolean = (this eq x$1.asInstanceOf[AnyRef]) || ((x$1 match {
    case _: UC2 => true
    case _ => false
  }) && {
    val UC2$1 = x$1.asInstanceOf[UC2]
    a == UC2$1.a && b == UC2$1.b
  })
}

final case class FZC() {
  override def canEqual(o: Any): Boolean = false
}
