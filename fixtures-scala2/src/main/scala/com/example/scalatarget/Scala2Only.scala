package com.example.scalatarget

case class U0()

object U0 {
  def unapply(x: U0): Boolean = true
}

case class Id(s: String)

object Id {
  def unapply(x: Id): Id = x
}

case class FP(a: Int)

object FP {
  def fromProduct(p: Product): FP = new FP(p.productElement(0).asInstanceOf[Int])
}
