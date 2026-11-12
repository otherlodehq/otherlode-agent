package com.example.scalatarget

class NestedSingletons {
  enum Compass {
    case North, East, South, West
  }

  enum Side {
    case Left, Right
  }
}

class NestedMixed {
  enum Token {
    case Word(text: String)
    case Space, Tab
  }
}

class NestedParam {
  enum Rank(val points: Int) {
    case Low extends Rank(1)
    case High extends Rank(5)
  }
}

class NestedCompanion {
  enum Gate {
    case Open, Closed
  }

  object Gate {
    val fallback: Gate = Gate.Open
    def parse(s: String): Gate = if (s == "open") Gate.Open else Gate.Closed
  }
}

class NestedSharedName {
  object Word

  enum Token {
    case Word(text: String)
    case Blank
  }
}

class NestedSharedNameAgain {
  enum Token {
    case Word(text: String)
    case Gap
  }
}

class NestedAnnotated {
  enum Era {
    @deprecated case Old
    @transient case Mid
    case New
  }
}

class NestedCompanionHint {
  enum Mode {
    case Fast, Slow
  }

  object Mode {
    final case class Hint(x: Int)
  }
}
