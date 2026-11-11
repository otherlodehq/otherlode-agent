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
import kotlin.test.Test
import kotlin.test.assertEquals

class BranchDropRewriterTest {
    private fun loadWithDrop(
        capacity: Int,
        droppedOrdinalsByMethod: (String, String) -> Set<Int>,
        eligibleMethod: String = "classify",
        fixture: String = "BranchTargetWithExtraBranches",
    ): Pair<Class<*>, LongArray> {
        val classesDir = File("build/classes/java/test")
        val locator = ClassFileLocator.Compound(ClassFileLocator.ForFolder(classesDir), ClassFileLocator.ForClassLoader.ofSystemLoader())
        val typePool = TypePool.Default.of(locator)
        val typeDescription = typePool.describe("com.example.target.$fixture").resolve()
        val counts = LongArray(capacity)
        val woven = "com/example/dropguard/$fixture$eligibleMethod$capacity"
        val loaded =
            ByteBuddy()
                .redefine<Any>(typeDescription, locator)
                .name(woven.replace('/', '.'))
                .defineField(ProbeArrayForm.PROBE_ARRAY_FIELD, LongArray::class.java, Visibility.PRIVATE, Ownership.STATIC)
                .initializer(LoadedTypeInitializer.ForStaticField(ProbeArrayForm.PROBE_ARRAY_FIELD, counts))
                .visit(
                    BranchProbeAsmVisitorWrapper(
                        eligibleMethods = { name, _ -> name == eligibleMethod },
                        probeArray = FieldProbeArrayLoad(woven),
                        probeIndexBase = 0,
                        branchSlotCapacity = capacity,
                        droppedOrdinalsByMethod = droppedOrdinalsByMethod,
                    ),
                ).make()
                .load(javaClass.classLoader, ClassLoadingStrategy.Default.WRAPPER)
                .loaded
        return loaded to counts
    }

    @Test
    fun `a dropped ordinal is emitted unchanged and allocates no slot, so the kept site still lands on slot zero`() {
        val (loaded, counts) =
            loadWithDrop(
                capacity = 2,
                droppedOrdinalsByMethod = { name, _ -> if (name == "classify") setOf(0) else emptySet() },
            )
        val target = loaded.getDeclaredConstructor().newInstance()
        val classify = loaded.getMethod("classify", Int::class.java)

        assertEquals("large", classify.invoke(target, 500), "the dropped first jump still runs, just with no probe")
        assertEquals("non-positive", classify.invoke(target, -5))
        assertEquals("positive", classify.invoke(target, 50))

        assertEquals(listOf(1L, 1L), counts.toList())
    }

    @Test
    fun `a dropped lookupswitch still advances the site ordinal, so the conditional after it keeps its own slots`() {
        val (loaded, counts) =
            loadWithDrop(
                capacity = 2,
                droppedOrdinalsByMethod = { name, _ -> if (name == "classify") setOf(0) else emptySet() },
                fixture = "SwitchThenBranchTarget",
            )
        val target = loaded.getDeclaredConstructor().newInstance()
        val classify = loaded.getMethod("classify", Int::class.java)

        assertEquals(10, classify.invoke(target, 1), "a case still reaches its own body")
        assertEquals(20, classify.invoke(target, 1000))
        assertEquals(40, classify.invoke(target, 7), "the default falls through to the conditional")
        assertEquals(30, classify.invoke(target, 10_000))

        assertEquals(listOf(1L, 1L), counts.toList(), "the kept conditional owns slots 0 and 1")
    }
}
