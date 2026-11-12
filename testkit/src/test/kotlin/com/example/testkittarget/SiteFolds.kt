package com.example.testkittarget

class SiteFolds {
    fun neverCalled(
        flag: Boolean,
        text: String?,
    ): Int {
        val length = text?.length ?: 0
        return if (flag) length + 1 else length - 1
    }

    fun nested(
        outer: Boolean,
        middle: Boolean,
        inner: Boolean,
    ): Int {
        var total = 0
        if (outer) {
            total += 1
            if (middle) {
                total += 2
                if (inner) total += 4
            }
        }
        return total
    }

    fun guardRan(
        first: Boolean,
        second: Boolean,
    ): Int {
        var total = 0
        if (first) {
            total += 1
            if (second) total += 2
        }
        return total
    }

    fun behindRoutine(
        count: Int?,
        flag: Boolean,
    ): Int = count ?: if (flag) 1 else 2
}

class SiteFoldsLoneConstructor(
    flag: Boolean,
) {
    init {
        if (flag) Thread.yield()
    }
}
