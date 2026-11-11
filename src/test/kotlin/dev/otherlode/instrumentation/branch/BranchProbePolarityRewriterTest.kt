package dev.otherlode.instrumentation.branch

import dev.otherlode.instrumentation.ProbeArrayForm
import net.bytebuddy.ByteBuddy
import net.bytebuddy.description.modifier.Ownership
import net.bytebuddy.description.modifier.Visibility
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy
import net.bytebuddy.implementation.LoadedTypeInitializer
import net.bytebuddy.pool.TypePool
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BranchProbePolarityRewriterTest {
    private val loads = AtomicInteger()

    private fun load(
        capacity: Int,
        eligibleMethod: (String) -> Boolean = { it == "classify" },
        swappedOrdinalsByMethod: (String, String) -> Set<Int> = { _, _ -> emptySet() },
        unprobedOutcomesByMethod: (String, String) -> Map<Int, Int> = { _, _ -> emptyMap() },
        slotsByMethod: ((String, String) -> BranchProbeAsmVisitorWrapper.MethodSlots?)? = null,
    ): Pair<Class<*>, LongArray> {
        val classesDir = File("build/classes/java/test")
        val locator = ClassFileLocator.Compound(ClassFileLocator.ForFolder(classesDir), ClassFileLocator.ForClassLoader.ofSystemLoader())
        val typeDescription =
            TypePool.Default
                .of(locator)
                .describe("com.example.target.BranchTarget")
                .resolve()
        val counts = LongArray(capacity)
        val woven = "com/example/polarity/BranchTarget${loads.incrementAndGet()}"
        val loaded =
            ByteBuddy()
                .redefine<Any>(typeDescription, locator)
                .name(woven.replace('/', '.'))
                .defineField(ProbeArrayForm.PROBE_ARRAY_FIELD, LongArray::class.java, Visibility.PRIVATE, Ownership.STATIC)
                .initializer(LoadedTypeInitializer.ForStaticField(ProbeArrayForm.PROBE_ARRAY_FIELD, counts))
                .visit(
                    BranchProbeAsmVisitorWrapper(
                        eligibleMethods = { name, _ -> eligibleMethod(name) },
                        probeArray = FieldProbeArrayLoad(woven),
                        probeIndexBase = 0,
                        branchSlotCapacity = capacity,
                        unprobedOutcomesByMethod = unprobedOutcomesByMethod,
                        swappedOrdinalsByMethod = swappedOrdinalsByMethod,
                        slotsByMethod = slotsByMethod,
                    ),
                ).make()
                .load(javaClass.classLoader, ClassLoadingStrategy.Default.WRAPPER)
                .loaded
        return loaded to counts
    }

    private fun classify(
        loaded: Class<*>,
        vararg values: Int,
    ) {
        val target = loaded.getDeclaredConstructor().newInstance()
        val classify = loaded.getMethod("classify", Int::class.java)
        values.forEach { classify.invoke(target, it) }
    }

    @Test
    fun `an unswapped jump counts its taken edge at offset zero`() {
        val (loaded, counts) = load(capacity = 2)

        classify(loaded, -5, 5, 6)

        assertEquals(listOf(1L, 2L), counts.toList())
    }

    @Test
    fun `a swapped jump counts its taken edge in the fall-through slot and its fall-through in the taken slot`() {
        val (loaded, counts) = load(capacity = 2, swappedOrdinalsByMethod = { name, _ -> if (name == "classify") setOf(0) else emptySet() })

        classify(loaded, -5, 5, 6)

        assertEquals(listOf(2L, 1L), counts.toList(), "the received taken edge is the class file's fall-through")
    }

    @Test
    fun `a swapped jump with an unprobed outcome leaves the class file's unprobed outcome without a probe`() {
        val (loaded, counts) =
            load(
                capacity = 1,
                swappedOrdinalsByMethod = { name, _ -> if (name == "classify") setOf(0) else emptySet() },
                unprobedOutcomesByMethod = { name, _ -> if (name == "classify") mapOf(0 to 0) else emptyMap() },
            )

        classify(loaded, -5, 5, 6)

        assertEquals(listOf(1L), counts.toList(), "only the received taken edge, the class file's fall-through, counts")
    }

    @Test
    fun `a swapped jump with the class file's fall-through unprobed counts only the received fall-through`() {
        val (loaded, counts) =
            load(
                capacity = 1,
                swappedOrdinalsByMethod = { name, _ -> if (name == "classify") setOf(0) else emptySet() },
                unprobedOutcomesByMethod = { name, _ -> if (name == "classify") mapOf(0 to 1) else emptyMap() },
            )

        classify(loaded, -5, 5, 6)

        assertEquals(listOf(2L), counts.toList(), "only the received fall-through, the class file's taken edge, counts")
    }

    @Test
    fun `per-method slot bases place each method's slots where the analysis put them, whatever order the methods arrive in`() {
        val slots =
            mapOf(
                "classifyDense" to BranchProbeAsmVisitorWrapper.MethodSlots(0, 4),
                "classifySparse" to BranchProbeAsmVisitorWrapper.MethodSlots(4, 3),
                "classify" to BranchProbeAsmVisitorWrapper.MethodSlots(7, 2),
            )
        val (loaded, counts) =
            load(
                capacity = 9,
                eligibleMethod = { it in slots },
                slotsByMethod = { name, _ -> slots[name] },
            )

        classify(loaded, -5)

        assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 0L, 0L, 1L, 0L), counts.toList())
    }

    @Test
    fun `a method asking for a slot count other than its own fails the rewrite even when the class total matches`() {
        val slots =
            mapOf(
                "classify" to BranchProbeAsmVisitorWrapper.MethodSlots(0, 3),
                "classifyDense" to BranchProbeAsmVisitorWrapper.MethodSlots(3, 3),
            )

        val failure =
            assertFailsWith<IllegalStateException> {
                load(capacity = 6, eligibleMethod = { it in slots }, slotsByMethod = { name, _ -> slots[name] })
            }

        assertTrue(failure.message!!.contains("classify(I)Ljava/lang/String; wants 2 branch probe slots"), failure.message)
    }
}
