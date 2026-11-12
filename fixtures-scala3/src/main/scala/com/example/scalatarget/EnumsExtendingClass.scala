package com.example.scalatarget

abstract class Tagged(val tag: String)

enum Tone extends Tagged("tone") {
  case Soft, Loud
}

enum Mark extends Tagged("mark") {
  case Plain
  case Scored(points: Int)
}
