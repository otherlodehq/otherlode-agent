package com.example.scalatarget

/** A plain Scala 3 enum: singleton cases only. See agent ADR 0048. */
enum Suit {
  case Hearts, Spades, Clubs
}

/** An enum with a constructor parameter, every case extending it. */
enum Planet(val mass: Int) {
  case Mercury extends Planet(3)
  case Venus extends Planet(5)
}

/** An enum with a parameterised case beside singleton ones. */
enum Shape {
  case Circle(radius: Double)
  case Square(side: Int, label: String)
  case Dot
  case Origin
}

/** An enum with hand-written methods, one of them overloading scalac's `valueOf`. */
enum Level {
  case Low, High

  def next: Level = if (this == Low) High else Low
  def valueOf(s: String, n: Int): Level = Low
  def ordinalPlus(n: Int): Int = ordinal + n
}

/** The companion of an enum, holding hand-written methods. */
object Level {
  def parse(s: String): Level = if (s == "low") Low else High
  def values(n: Int): Array[Level] = Array(Low, High)
}

/** An enum declared inside an object. */
object EnumHost {
  enum Mode {
    case On, Off
  }
}

/** An enum whose companion holds a given instance, a nested object and a nested case class beside the cases. */
enum Tint {
  case Dark, Light
}

object Tint {
  given Ordering[Tint] with {
    def compare(a: Tint, b: Tint): Int = a.ordinal - b.ordinal
  }

  object Codes {
    val dark: Int = 1
  }

  case class Swatch(i: Int)
}

/** Three singleton cases beside a parameterised one, so `fromOrdinal` switches on the ordinal. */
enum Op {
  case Add, Sub, Mul
  case Lit(n: Int)
}

/** Only parameterised cases, so `fromOrdinal` only throws. */
enum Term {
  case Num(n: Int)
  case Neg(t: Term)
}

/** Case names whose hashes are dense, so `valueOf` lowers to a `tableswitch`. */
enum Axis3 {
  case X, Y, Z
}


/** Singleton ordinals with a gap a parameterised case fills, so `fromOrdinal`'s switch sends the gap to the throw. */
enum Gapped {
  case A
  case P(x: Int)
  case B, C
}

/** Case-name hashes 65, 67 and 68, so `valueOf`'s `tableswitch` sends 66 to the throw. */
enum Sparse {
  case A, C, D
}

/** An enum declared inside a class, whose companion holds no `MODULE$`: scalac's plumbing the rules do not read. */
class EnumHolder {
  enum Inner {
    case Up, Down
  }

  def first: Inner = Inner.Up
}
