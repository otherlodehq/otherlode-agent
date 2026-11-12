package com.example.scalatarget

object Extensions {
  extension (n: Int)
    def pad(width: Int = 5): String = {
      val s = n.toString
      if (s.length >= width) s else ("0" * (width - s.length)) + s
    }
}

object InlineHost {
  inline def inlineF(a: Int, b: Int = 7): Int = a + b
}

enum Color(val code: Int = 0) {
  case Red extends Color(1)
  case Blue
}

object Driver3 {
  def callExtensionAllOmitted(): String = {
    import Extensions.pad
    (5).pad()
  }

  def callExtensionNoneOmitted(): String = {
    import Extensions.pad
    (5).pad(3)
  }

  def callInline(): Int = InlineHost.inlineF(1)
}

case class Acc(private val a: Int) {
  def a$access$0: Int = a
}
