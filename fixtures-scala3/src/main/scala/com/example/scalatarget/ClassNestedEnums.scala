package com.example.scalatarget

/** Enums declared inside a class, whose companions hold no `MODULE$`: singleton cases only, enough of them for a hash switch. */
class NestedSingletons {
  enum Compass {
    case North, East, South, West
  }

  enum Side {
    case Left, Right
  }
}

/** A class-nested enum with a parameterised case beside singletons. */
class NestedMixed {
  enum Token {
    case Word(text: String)
    case Space, Tab
  }
}

/** A class-nested enum with a constructor parameter, every case extending it. */
class NestedParam {
  enum Rank(val points: Int) {
    case Low extends Rank(1)
    case High extends Rank(5)
  }
}

/** A class-nested enum whose explicit companion holds a hand-written value of the enum type and a method. */
class NestedCompanion {
  enum Gate {
    case Open, Closed
  }

  object Gate {
    val fallback: Gate = Gate.Open
    def parse(s: String): Gate = if (s == "open") Gate.Open else Gate.Closed
  }
}

/** A class-nested enum whose parameterised case shares its name with a member object declared before it in the file. */
class NestedSharedName {
  object Word

  enum Token {
    case Word(text: String)
    case Blank
  }
}

/** A class-nested enum with a parameterised case named like one earlier in the file, so its object's field is numbered past 1. */
class NestedSharedNameAgain {
  enum Token {
    case Word(text: String)
    case Gap
  }
}

/** A class-nested enum whose singleton cases carry annotations that add field flags. */
class NestedAnnotated {
  enum Era {
    @deprecated case Old
    @transient case Mid
    case New
  }
}

/** A class-nested enum whose explicit companion holds a final case class of the adopter's. */
class NestedCompanionHint {
  enum Mode {
    case Fast, Slow
  }

  object Mode {
    final case class Hint(x: Int)
  }
}
