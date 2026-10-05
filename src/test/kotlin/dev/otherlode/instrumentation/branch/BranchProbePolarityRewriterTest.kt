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

/**
 * Drives [BranchProbeAsmVisitorWrapper] with a swapped ordinal and with per-method slot bases, on
 * `BranchTarget`, whose `classify` is one `IFLE` (javac's spelling of `value > 0`). A swapped
 * ordinal stands for a received jump that tests the opposite of the class file's, so its taken edge
 * must count the class file's fall-through slot and its fall-through the taken slot.
 */
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

        // IFLE is taken for -5 and falls through for 5 and 6.
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
        // Offset 0 unprobed in the class file's numbering: its taken edge gets no probe and its
        // fall-through the one slot. Swapped, the received fall-through is the class file's taken
        // edge, so it is the received taken edge that counts.
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
        // Offset 1 unprobed in the class file's numbering: its fall-through gets no probe and its
        // taken edge the one slot. Swapped, the class file's taken edge is the received
        // fall-through, so the received taken edge goes uncounted.
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
        // classify's two slots go after classifyDense's four and classifySparse's three, although
        // classify is the first method the rewriter meets.
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
        // classify wants 2 and classifyDense 4. Sized as 3 and 3, the total of 6 matches, but
        // classify would leave a slot of its own unused and classifyDense would ask for more than
        // its run.
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
