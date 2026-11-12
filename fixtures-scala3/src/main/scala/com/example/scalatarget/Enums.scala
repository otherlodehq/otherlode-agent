package com.example.scalatarget

enum Suit {
  case Hearts, Spades, Clubs
}

enum Planet(val mass: Int) {
  case Mercury extends Planet(3)
  case Venus extends Planet(5)
}

enum Shape {
  case Circle(radius: Double)
  case Square(side: Int, label: String)
  case Dot
  case Origin
}

enum Level {
  case Low, High

  def next: Level = if (this == Low) High else Low
  def valueOf(s: String, n: Int): Level = Low
  def ordinalPlus(n: Int): Int = ordinal + n
}

object Level {
  def parse(s: String): Level = if (s == "low") Low else High
  def values(n: Int): Array[Level] = Array(Low, High)
}

object EnumHost {
  enum Mode {
    case On, Off
  }
}

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

enum Op {
  case Add, Sub, Mul
  case Lit(n: Int)
}

enum Term {
  case Num(n: Int)
  case Neg(t: Term)
}

enum Axis3 {
  case X, Y, Z
}


enum Gapped {
  case A
  case P(x: Int)
  case B, C
}

enum Sparse {
  case A, C, D
}

class EnumHolder {
  enum Inner {
    case Up, Down
  }

  def first: Inner = Inner.Up
}

object CaseHost {
  enum Kind {
    case Box(n: Int)
    case Empty
  }
}

enum Coin(val v: Int) {
  case Custom(n: Int) extends Coin(n)
  case Penny extends Coin(1)
}
