package com.example.scalatarget

/** Enums declared in the methods of an object: singleton cases only, a parameterised case beside singletons, and cases extending the constructor. */
object LocalObjectHost {
  def singletons(): Int = {
    enum Pip {
      case Hearts, Spades, Clubs
    }
    Pip.valueOf("Spades").ordinal + Pip.fromOrdinal(2).ordinal + Pip.values.length + Pip.Hearts.productPrefix.length
  }

  def mixed(): Int = {
    enum Form {
      case Disc(radius: Double)
      case Dot
      case Origin
    }
    Form.fromOrdinal(1).ordinal + Form.Disc(1.0).ordinal + Form.Origin.ordinal
  }

  def withParam(): Int = {
    enum Orb(val mass: Int) {
      case Mercury extends Orb(3)
      case Venus extends Orb(5)
    }
    Orb.Venus.mass + Orb.Mercury.ordinal
  }

  def withCompanion(): Int = {
    enum Door {
      case Open, Shut
    }
    object Door {
      def parse(s: String): Door = if (s == "open") Door.Open else Door.Shut
      def ordinal(x: Door): Int = x.ordinal + 10
    }
    Door.parse("open").ordinal + Door.ordinal(Door.Shut)
  }

  def lookalike(): Int = {
    enum Fake {
      case A, B
    }
    type OFake = Fake
    val r = {
      object Fake extends scala.deriving.Mirror.Sum {
        type MirroredMonoType = OFake
        type MirroredType = OFake
        type MirroredLabel = "Fake"
        type MirroredElemTypes = EmptyTuple
        type MirroredElemLabels = EmptyTuple
        def ordinal(x: OFake): Int = x.ordinal
        def fromOrdinal(i: Int): OFake = null.asInstanceOf[OFake]
        def values: Array[OFake] = Array()
        def valueOf(s: String): OFake = null.asInstanceOf[OFake]
      }
      Fake.fromOrdinal(0)
    }
    if (r == null) Fake.A.ordinal else Fake.B.ordinal
  }
}

/** Enums declared in the methods of a class, in a lambda, and two of one name in two methods. */
class LocalClassHost {
  def singletons(): Int = {
    enum Rune {
      case Fehu, Uruz, Thurs
    }
    Rune.valueOf("Uruz").ordinal + Rune.fromOrdinal(2).ordinal + Rune.values.length
  }

  def mixed(): Int = {
    enum Term {
      case Num(n: Int)
      case Nil
    }
    Term.Num(1).ordinal + Term.fromOrdinal(1).ordinal
  }

  def inLambda(): Int = {
    val f = (i: Int) => {
      enum Beat {
        case Up, Down
      }
      Beat.fromOrdinal(i).ordinal
    }
    f(1)
  }

  def sameA(): Int = {
    enum Twin {
      case A, B
    }
    Twin.A.ordinal + Twin.valueOf("B").ordinal
  }

  def sameB(): Int = {
    enum Twin {
      case X, Y, Z
    }
    Twin.Z.ordinal + Twin.valueOf("Y").ordinal
  }

  def withParam(): Int = {
    enum Gem(val weight: Int) {
      case Ruby extends Gem(4)
      case Opal extends Gem(7)
    }
    Gem.Opal.weight + Gem.Ruby.ordinal
  }
}

/** A trait whose method declares a local enum, so the host's lazy accessor is an interface method. */
trait LocalTraitHost {
  def inTrait(): Int = {
    enum Tide {
      case Ebb, Flow
    }
    Tide.Flow.ordinal
  }
}

/** A companion trait declaring `fromOrdinal` generically. */
trait LocalEnumCompanion[E] {
  def fromOrdinal(i: Int): E
}

/** A local enum whose explicit companion extends a generic trait declaring `fromOrdinal`, so scalac adds a bridge. */
object LocalGenericHost {
  def generic(): Int = {
    enum Shade {
      case Light, Dark
    }
    object Shade extends LocalEnumCompanion[Shade]
    def first[T](c: LocalEnumCompanion[T]): T = c.fromOrdinal(0)
    first(Shade).ordinal
  }
}

/** A class implementing the trait host, so its local enum is used. */
class LocalTraitUser extends LocalTraitHost
