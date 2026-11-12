package com.example.outside

import com.example.target.kotlinc.Describer

open class OutsideDescriber : Describer {
    override fun id(): String = "outside"
}
