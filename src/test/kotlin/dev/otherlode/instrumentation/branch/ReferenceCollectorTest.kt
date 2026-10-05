package dev.otherlode.instrumentation.branch

import net.bytebuddy.jar.asm.Type
import kotlin.test.Test
import kotlin.test.assertEquals

class ReferenceCollectorTest {
    private fun collected(vararg descriptors: String): List<String> = collected(false, *descriptors)

    private fun collected(
        remembering: Boolean,
        vararg descriptors: String,
    ): List<String> {
        val names = LinkedHashSet<String>()
        val collector = ReferenceCollector(names, remembering)
        descriptors.forEach(collector::descriptor)
        return names.toList()
    }

    private fun viaType(vararg descriptors: String): List<String> {
        val names = LinkedHashSet<String>()

        fun add(type: Type) {
            when (type.sort) {
                Type.ARRAY -> {
                    add(type.elementType)
                }

                Type.OBJECT -> {
                    names += type.internalName
                }

                Type.METHOD -> {
                    type.argumentTypes.forEach(::add)
                    add(type.returnType)
                }
            }
        }
        descriptors.forEach { add(Type.getType(it)) }
        return names.toList()
    }

    @Test
    fun `descriptors name the same classes in the same order as ASM's type parser`() {
        val descriptors =
            arrayOf(
                "I",
                "Ljava/lang/String;",
                "[[Lcom/acme/Foo;",
                "[J",
                "()V",
                "(ILjava/util/List;[Lcom/acme/Bar;J)Lcom/acme/Baz\$Inner;",
                "(Lcom/acme/A;Lcom/acme/B;Lcom/acme/A;)[Lcom/acme/C;",
                "(Ljava/lang/Object;DLjava/lang/Object;)Z",
            )

        assertEquals(viaType(*descriptors), collected(*descriptors))
    }

    @Test
    fun `a descriptor read twice adds its classes once and keeps first-seen order`() {
        val first = "(Lcom/acme/A;Lcom/acme/B;)V"
        val second = "(Lcom/acme/C;Lcom/acme/A;)V"

        assertEquals(listOf("com/acme/A", "com/acme/B", "com/acme/C"), collected(first, second, first, second))
        assertEquals(listOf("com/acme/A", "com/acme/B", "com/acme/C"), collected(true, first, second, first, second))
    }

    @Test
    fun `a remembering collector gives the same names as one that does not, past its memory`() {
        val descriptors = (0 until 40).map { "(Lcom/acme/T${it % 13};I)Lcom/acme/R${it % 5};" }.toTypedArray()

        assertEquals(collected(false, *descriptors), collected(true, *descriptors))
    }

    @Test
    fun `an array internal name reduces to its element type`() {
        val names = LinkedHashSet<String>()
        val collector = ReferenceCollector(names)
        collector.internalName("[Lcom/acme/Foo;")
        collector.internalName("com/acme/Bar")
        collector.internalName("[I")

        assertEquals(listOf("com/acme/Foo", "com/acme/Bar"), names.toList())
    }
}
