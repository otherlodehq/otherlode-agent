package com.example.outside

import com.example.target.kotlinc.Describer

/** An open class outside the include rules implementing [Describer], so a walk up to it stops. */
open class OutsideDescriber : Describer {
    override fun id(): String = "outside"
}
