package dev.otherlode.instrumentation

import net.bytebuddy.jar.asm.Attribute
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.Opcodes

/**
 * Detects the marker scalac writes onto every class file it compiles: a `Scala` class attribute
 * for Scala 3, or a `ScalaSig` class attribute for Scala 2's pickled signature. ASM has no built-in
 * handling for either name, so a class carrying one still reaches [ClassVisitor.visitAttribute] as
 * a generic, unparsed attribute; only its type name is read here.
 *
 * [TypeMatchPolicy.methodMatcher] uses this to tell a scalac lambda body (`$anonfun$...`, or
 * Scala 3's `<owner>$$anonfun$N`) apart from an unrelated synthetic method of the same shape on a
 * class scalac never compiled, which must stay excluded.
 */
object ScalaClassDetector {
    private val SCALA_ATTRIBUTE_TYPES = setOf("Scala", "ScalaSig")

    /** Whether [attribute], a class attribute ASM did not parse, is one scalac writes. */
    fun isScalaAttribute(attribute: Attribute): Boolean = attribute.type in SCALA_ATTRIBUTE_TYPES

    /**
     * Whether [classBytes] carries a `Scala` or `ScalaSig` class attribute.
     *
     * Reads the class file's structure directly, allocating nothing: the only class attribute names
     * that matter are the two constant-pool entries spelling them, so the walk remembers those
     * indexes and compares each class attribute's name index against them. A class file this walk
     * cannot follow goes to the ASM reader, which answers or throws as it always did.
     */
    fun isScalaClass(classBytes: ByteArray): Boolean = scanClassAttributes(classBytes) ?: readWithAsm(classBytes)

    private fun scanClassAttributes(bytes: ByteArray): Boolean? {
        try {
            val poolCount = u2(bytes, POOL_COUNT_OFFSET)
            var scalaIndex = 0
            var scalaSigIndex = 0
            var offset = POOL_OFFSET
            var index = 1
            while (index < poolCount) {
                when (bytes[offset].toInt() and BYTE_MASK) {
                    TAG_UTF8 -> {
                        val length = u2(bytes, offset + 1)
                        if (length == SCALA.length && matches(bytes, offset + UTF8_HEADER, SCALA)) scalaIndex = index
                        if (length == SCALA_SIG.length && matches(bytes, offset + UTF8_HEADER, SCALA_SIG)) scalaSigIndex = index
                        offset += UTF8_HEADER + length
                    }

                    TAG_LONG, TAG_DOUBLE -> {
                        offset += WIDE_ENTRY
                        index++
                    }

                    TAG_CLASS, TAG_STRING, TAG_METHOD_TYPE, TAG_MODULE, TAG_PACKAGE -> {
                        offset += SHORT_ENTRY
                    }

                    TAG_METHOD_HANDLE -> {
                        offset += HANDLE_ENTRY
                    }

                    TAG_FIELD, TAG_METHOD, TAG_INTERFACE_METHOD, TAG_INTEGER, TAG_FLOAT, TAG_NAME_AND_TYPE,
                    TAG_DYNAMIC, TAG_INVOKE_DYNAMIC,
                    -> {
                        offset += INT_ENTRY
                    }

                    else -> {
                        return null
                    }
                }
                index++
            }
            if (scalaIndex == 0 && scalaSigIndex == 0) return false
            offset += CLASS_HEADER
            offset += u2(bytes, offset) * 2 + 2
            repeat(2) {
                val members = u2(bytes, offset)
                offset += 2
                repeat(members) {
                    offset += MEMBER_HEADER
                    offset = skipAttributes(bytes, offset)
                }
            }
            val count = u2(bytes, offset)
            offset += 2
            repeat(count) {
                val name = u2(bytes, offset)
                if (name != 0 && (name == scalaIndex || name == scalaSigIndex)) return true
                offset += ATTRIBUTE_HEADER + u4(bytes, offset + 2)
            }
            return false
        } catch (_: IndexOutOfBoundsException) {
            return null
        }
    }

    private fun skipAttributes(
        bytes: ByteArray,
        start: Int,
    ): Int {
        var offset = start
        val count = u2(bytes, offset)
        offset += 2
        repeat(count) { offset += ATTRIBUTE_HEADER + u4(bytes, offset + 2) }
        return offset
    }

    private fun matches(
        bytes: ByteArray,
        offset: Int,
        text: String,
    ): Boolean = text.indices.all { bytes[offset + it].toInt() == text[it].code }

    private fun u2(
        bytes: ByteArray,
        offset: Int,
    ): Int = ((bytes[offset].toInt() and BYTE_MASK) shl 8) or (bytes[offset + 1].toInt() and BYTE_MASK)

    private fun u4(
        bytes: ByteArray,
        offset: Int,
    ): Int = (u2(bytes, offset) shl 16) or u2(bytes, offset + 2)

    private fun readWithAsm(classBytes: ByteArray): Boolean {
        var found = false
        val visitor =
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitAttribute(attribute: Attribute) {
                    if (isScalaAttribute(attribute)) found = true
                }
            }
        ClassReader(classBytes).accept(visitor, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return found
    }

    private const val SCALA = "Scala"
    private const val SCALA_SIG = "ScalaSig"
    private const val POOL_COUNT_OFFSET = 8
    private const val POOL_OFFSET = 10
    private const val BYTE_MASK = 0xFF
    private const val UTF8_HEADER = 3
    private const val WIDE_ENTRY = 9
    private const val SHORT_ENTRY = 3
    private const val HANDLE_ENTRY = 4
    private const val INT_ENTRY = 5
    private const val CLASS_HEADER = 6
    private const val MEMBER_HEADER = 6
    private const val ATTRIBUTE_HEADER = 6
    private const val TAG_UTF8 = 1
    private const val TAG_INTEGER = 3
    private const val TAG_FLOAT = 4
    private const val TAG_LONG = 5
    private const val TAG_DOUBLE = 6
    private const val TAG_CLASS = 7
    private const val TAG_STRING = 8
    private const val TAG_FIELD = 9
    private const val TAG_METHOD = 10
    private const val TAG_INTERFACE_METHOD = 11
    private const val TAG_NAME_AND_TYPE = 12
    private const val TAG_METHOD_HANDLE = 15
    private const val TAG_METHOD_TYPE = 16
    private const val TAG_DYNAMIC = 17
    private const val TAG_INVOKE_DYNAMIC = 18
    private const val TAG_MODULE = 19
    private const val TAG_PACKAGE = 20
}
