package com.example.target

/** A generic value class, the shape of `kotlin.Result`: its static members carry the class's type variable in their signature. */
@JvmInline
value class GenericBox<out T>(
    private val raw: Any?,
) {
    @Suppress("UNCHECKED_CAST")
    private fun unwrap(): T? = raw as T?

    /** Reads the boxed value through the private static member. */
    fun read(): T? = unwrap()

    /** Whether anything is boxed. */
    fun isPresent(): Boolean = raw != null
}

/** Uses [GenericBox] from an ordinary class, so the value class's own members are reached through its static members. */
class GenericBoxUser {
    /** Boxes [value], reads it back and reports whether it was present. */
    fun roundTrip(value: String?): String? {
        val box = GenericBox<String>(value)
        return if (box.isPresent()) box.read() else null
    }
}
