package com.example.scalatarget

enum Dir extends java.lang.Enum[Dir] {
  case North, South, East
}

enum Metal(val mass: Int) extends java.lang.Enum[Metal] {
  case Iron extends Metal(3)
  case Gold extends Metal(5)
}

object JavaEnumHost {
  enum Gear extends java.lang.Enum[Gear] {
    case Low, High
  }
}
