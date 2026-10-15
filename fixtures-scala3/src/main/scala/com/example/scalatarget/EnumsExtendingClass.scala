package com.example.scalatarget

/** A class an enum extends, as an adopter writes one. */
abstract class Tagged(val tag: String)

/** An enum extending a class of the adopter's: singleton cases only. */
enum Tone extends Tagged("tone") {
  case Soft, Loud
}

/** An enum extending a class of the adopter's, with a parameterised case beside a singleton. */
enum Mark extends Tagged("mark") {
  case Plain
  case Scored(points: Int)
}
