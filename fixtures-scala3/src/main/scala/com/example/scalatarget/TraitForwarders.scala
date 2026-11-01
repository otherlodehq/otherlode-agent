package com.example.scalatarget

trait Greets {
  def greet(name: String): String = "hi " + name
  def total(a: Int, b: Long, c: Double): Long = a + b + c.toLong
  def ping(): Unit = ()
}

trait LoudGreets extends Greets {
  def loud(name: String): String = greet(name).toUpperCase
}

/** Mixes `Greets` in and writes no method of its own. */
class Greeter extends Greets

/** Overrides `greet` itself, so its `greet` is the adopter's. */
class OverridingGreeter extends Greets {
  override def greet(name: String): String = "HI " + name
}

/** Overrides `greet` with its own work around a `super` call, so its `greet` calls the trait's static entry. */
class WrappingGreeter extends Greets {
  override def greet(name: String): String = super.greet(name) + "!"
}

/** Mixes in a trait that extends `Greets`, so `Greets`' methods reach it through `LoudGreets`. */
class DeepGreeter extends LoudGreets

/** Implements a Java interface with default methods, so scalac writes a forwarder for each default. */
class ComparingHandler extends java.util.Comparator[String] {
  def compare(a: String, b: String): Int = 0
}

class EvenLength extends java.util.function.Predicate[String] {
  def test(s: String): Boolean = s.length % 2 == 0
}

class Shout extends java.util.function.Function[String, String] {
  def apply(s: String): String = s.toUpperCase
}

/** Calls a Java default under another name, so its `invokespecial` names a method it does not share. */
class RenamingHandler extends java.util.Comparator[String] {
  def compare(a: String, b: String): Int = 0
  def flipped(): java.util.Comparator[String] = super.reversed()
}

/** Overrides a Java default with a body of its own. */
class OverridingHandler extends java.util.Comparator[String] {
  def compare(a: String, b: String): Int = 0
  override def reversed(): java.util.Comparator[String] = null
}

/** A function whose body scalac moves into the specialised `apply$mcII$sp`. */
class Inc extends (Int => Int) {
  def apply(x: Int): Int = x + 1
}

/** A function over `Double`, which scalac specialises as it does `Int`. */
class Halve extends (Double => Double) {
  def apply(x: Double): Double = x / 2
}

trait StringOrder extends java.util.Comparator[String]

/** Reaches the Java interface through a trait, which Scala 3.9 also writes forwarders for. */
class TransitiveHandler extends StringOrder {
  def compare(a: String, b: String): Int = 0
}

/** Overrides a Java default with work around a `super` call, so its `reversed` is the adopter's. */
class WrappingHandler extends java.util.Comparator[String] {
  var calls = 0
  def compare(a: String, b: String): Int = 0
  override def reversed(): java.util.Comparator[String] = {
    calls += 1
    super.reversed()
  }
}

/** Overrides `Inc.apply`, so scalac writes both the twin and the forwarder again. */
class Inc2 extends Inc {
  override def apply(x: Int): Int = x + 2
}

/** Writes a method and a twin by hand, with no supertype that declares the twin. */
class Look {
  def foo(x: Int): Int = foo$mcII$sp(x)
  def foo$mcII$sp(x: Int): Int = x * 3
}
