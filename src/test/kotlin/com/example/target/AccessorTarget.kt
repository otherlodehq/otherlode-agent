package com.example.target

class AccessorTarget {
    private val x: Int = 3

    private fun secret(): Int = 9

    inner class Inner {
        fun callSecret(): Int = secret()

        fun readX(): Int = x
    }

    companion object {
        private fun companionSecret(): Int = 11
    }

    fun callCompanionSecret(): Int = companionSecret()
}
