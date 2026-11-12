package com.example.scalatarget

class Simple {
  def f(a: Int, b: Int = 1, c: String = "x"): Int = a + b + c.length
  def f(a: Int, b: Long): Int = a + b.toInt
}

class Curried {
  def g(a: Int)(b: Int = a + 1): Int = a + b
}

class ByNameHost {
  def h(a: => Int = 1): Int = a
}

class Generic {
  def firstOrNull[A >: Null <: AnyRef](a: A = null.asInstanceOf[A]): A = a
}

class FinalMethodHost {
  final def finalF(a: Int, b: Int = 2): Int = a + b
}

class PrivateMethodHost {
  private def priv(a: Int, b: Int = 3): Int = a + b
  def callPriv(a: Int): Int = priv(a)
}

class TwoListsHost {
  def twoLists(a: Int = 1)(b: Int = 2): Int = a + b
}

class SameNameDiffArityHost {
  def sameNameDiffArity(a: Int): Int = a
  def sameNameDiffArity(a: Int, b: Int, c: Int = 1): Int = a + b + c
}

class VarargHost {
  def withVararg(a: Int = 1)(rest: Int*): Int = a + rest.sum
}

trait Tr {
  def t(a: Int = 1): Int = a
}
class TrImpl extends Tr

object Obj {
  def m(a: Int, b: Int = 4): Int = a + b
}

case class Cc(a: Int = 1, b: Int = 2)

class Plain {
  def f(a: Int = 1): Int = a
}

class PlainOverridesBoth extends Plain {
  override def f(a: Int = 2): Int = a * 2
}

class PlainOverridesOnly extends Plain {
  override def f(a: Int): Int = a * 3
}

final class FinalHost {
  def f(a: Int, b: Int = 5): Int = a + b
}

object LambdaHost {
  def classify(value: Int): String = {
    val f: Int => String = v => if (v > 0) "positive" else "non-positive"
    f(value)
  }
}

class DefaultLines {
  def f(
      a: Int,
      b: Int = 1,
      c: String =
        "x"
  ): Int = a + b + c.length
}

case class Written(a: Int, b: String) {
  def this(b: String) =
    this(b.length, b)

  override def toString: String =
    s"Written($a, $b)"
}

object Written {
  def apply(s: String): Written =
    new Written(s.length, s)
}

class NotCase(val a: Int) { override def toString: String = "NotCase" }

object InlineLambdaHost {
  def label(values: List[Int]): List[String] = values.map(v => if (v > 0) "positive" else "other")

  def nested(values: List[Int]): List[Int] = values.flatMap(v => List(v).map(w => w + v))

  def viaHelper(values: List[Int]): List[Int] = values.map(double)

  private def double(v: Int): Int = v * 2
}

object NestedLambdaHost {
  class Inner {
    def bump(values: List[Int]): List[Int] = values.map(v => if (v > 0) v + 1 else v)

    def show(values: List[Int]): List[String] = values.map(v => v.toString)
  }
}
