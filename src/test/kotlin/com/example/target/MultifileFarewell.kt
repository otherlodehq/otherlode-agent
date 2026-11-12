@file:JvmMultifileClass
@file:JvmName("MultifileText")

package com.example.target

fun multifileFarewell(
    name: String,
    times: Int = 1,
): String = "bye $name".repeat(times)

val multifileSuffix: String = "!"
