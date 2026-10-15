package com.example.scalatarget

/** A Java-compatible Scala 3 enum: singleton cases only, extending `java.lang.Enum`. */
enum Dir extends java.lang.Enum[Dir] {
  case North, South, East
}

/** A Java-compatible enum with a constructor parameter, every case extending it. */
enum Metal(val mass: Int) extends java.lang.Enum[Metal] {
  case Iron extends Metal(3)
  case Gold extends Metal(5)
}

/** A Java-compatible enum declared inside an object. */
object JavaEnumHost {
  enum Gear extends java.lang.Enum[Gear] {
    case Low, High
  }
}
