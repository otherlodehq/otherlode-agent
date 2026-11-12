package com.example.target

import com.example.library.libraryInline

fun useCollections(values: List<Int>): Int? {
    val doubled = values.map { it * 2 } // marker: useCollections-map
    return doubled.firstOrNull { if (it > 10) true else false } // marker: useCollections-predicate
}

private inline fun sameFileInline(flag: Boolean): Int {
    if (flag) { // marker: sameFileInline-if
        return 1
    }
    return 0
}

fun callTakingTrueBranch(n: Int): Int = sameFileInline(n > 0) // marker: callTakingTrueBranch

fun callTakingFalseBranch(n: Int): Int = sameFileInline(n <= 0) // marker: callTakingFalseBranch

fun useLibraryInline(value: Int): Int = libraryInline(value) // marker: useLibraryInline

class SortKeys(
    val first: Int,
    val second: Int,
)

fun sortByBoth(items: List<SortKeys>): List<SortKeys> = items.sortedWith(compareBy<SortKeys> { it.first }.thenBy { it.second })

fun ownLoopBesideFilter(values: List<Int>): Int {
    var sum = 0
    for (value in values) { // marker: ownLoopBesideFilter-for
        sum += value
    }
    return sum + values.filter { it > 0 }.size
}
