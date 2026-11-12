package com.example.target

@JvmInline
value class GenericBox<out T>(
    private val raw: Any?,
) {
    @Suppress("UNCHECKED_CAST")
    private fun unwrap(): T? = raw as T?

    fun read(): T? = unwrap()

    fun isPresent(): Boolean = raw != null
}

class GenericBoxUser {
    fun roundTrip(value: String?): String? {
        val box = GenericBox<String>(value)
        return if (box.isPresent()) box.read() else null
    }
}
