package com.example.target

class GroupingTarget {
    fun lengthCounts(words: List<String>): Map<Int, Int> = words.groupingBy { it.length }.eachCount()
}
