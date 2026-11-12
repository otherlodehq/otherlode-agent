package com.example.scalatarget

trait Greets {
  def greet(name: String): String = "hi " + name
  def total(a: Int, b: Long, c: Double): Long = a + b + c.toLong
  def ping(): Unit = ()
}

trait LoudGreets extends Greets {
  def loud(name: String): String = greet(name).toUpperCase
}

class Greeter extends Greets

class OverridingGreeter extends Greets {
  override def greet(name: String): String = "HI " + name
}

class WrappingGreeter extends Greets {
  override def greet(name: String): String = super.greet(name) + "!"
}

class DeepGreeter extends LoudGreets

class ComparingHandler extends java.util.Comparator[String] {
  def compare(a: String, b: String): Int = 0
}

class EvenLength extends java.util.function.Predicate[String] {
  def test(s: String): Boolean = s.length % 2 == 0
}

class Shout extends java.util.function.Function[String, String] {
  def apply(s: String): String = s.toUpperCase
}

class RenamingHandler extends java.util.Comparator[String] {
  def compare(a: String, b: String): Int = 0
  def flipped(): java.util.Comparator[String] = super.reversed()
}

class OverridingHandler extends java.util.Comparator[String] {
  def compare(a: String, b: String): Int = 0
  override def reversed(): java.util.Comparator[String] = null
}

class Inc extends (Int => Int) {
  def apply(x: Int): Int = x + 1
}

class Halve extends (Double => Double) {
  def apply(x: Double): Double = x / 2
}

trait StringOrder extends java.util.Comparator[String]

class TransitiveHandler extends StringOrder {
  def compare(a: String, b: String): Int = 0
}

class WrappingHandler extends java.util.Comparator[String] {
  var calls = 0
  def compare(a: String, b: String): Int = 0
  override def reversed(): java.util.Comparator[String] = {
    calls += 1
    super.reversed()
  }
}

class Inc2 extends Inc {
  override def apply(x: Int): Int = x + 2
}

class Look {
  def foo(x: Int): Int = foo$mcII$sp(x)
  def foo$mcII$sp(x: Int): Int = x * 3
}
