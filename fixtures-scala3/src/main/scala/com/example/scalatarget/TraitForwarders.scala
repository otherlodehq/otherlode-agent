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
