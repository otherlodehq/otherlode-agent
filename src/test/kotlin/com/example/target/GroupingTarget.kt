package com.example.target

/** Inlines `groupingBy`, which leaves an anonymous `Grouping` whose `keyOf` has a generic signature unlike its erased descriptor. */
class GroupingTarget {
    /** Counts [words] by length. */
    fun lengthCounts(words: List<String>): Map<Int, Int> = words.groupingBy { it.length }.eachCount()
}
