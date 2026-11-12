package com.example.scalatarget

case class Multi(
    a: Int,
    b: String
) {
  override def toString: String =
    s"Multi($a)"
}

sealed trait Figure extends Product with Serializable

case class Round(r: Double) extends Figure

case class Box(w: Double, h: Double) extends Figure

case class Empty()

case object Solo

case class BodyVal(a: Int) {
  override def toString: String =
    "BodyVal"
  val later: Int = a + 1
}

case class HandCopy(
    a: Int
) {
  override def toString: String =
    "HandCopy"
  override def hashCode: Int =
    7
  def copy(a: Int = 2): HandCopy =
    new HandCopy(a)
}

class Pool(val size: Int)

case class Svc(
    a: Int,
    b: String
)(implicit
    pool: Pool
) {
  override def toString: String =
    "Svc"
}

case class TwoLists(
    a: Int
)(
    b: Int
)

class Outer {
  case class Inner(a: Int)

  def local(): Any = {
    case class Local(a: Int)
    Local(1)
  }
}

case class Priv(
    private val a: Int,
    protected val b: String
)

case class Mixed[T](
    l: Long,
    d: Double,
    z: Boolean,
    s: String,
    i: Int,
    f: Float,
    c: Char,
    by: Byte,
    sh: Short,
    t: T,
    o: Option[Int]
)

case class One(s: String)

case class Hidden(private val secret: Long, open: Boolean)

case class Lazy(a: Int, b: String) {
  lazy val twice: Int = a * 2
}
