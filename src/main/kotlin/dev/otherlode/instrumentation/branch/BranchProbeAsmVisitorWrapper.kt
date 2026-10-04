package dev.otherlode.instrumentation.branch

import dev.otherlode.instrumentation.ProbeArrayLoad
import net.bytebuddy.asm.AsmVisitorWrapper
import net.bytebuddy.description.field.FieldDescription
import net.bytebuddy.description.field.FieldList
import net.bytebuddy.description.method.MethodList
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.implementation.Implementation
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.pool.TypePool

/**
 * Hosts the branch-tracking tier's raw ASM rewrite inside ByteBuddy's own class-transform
 * pipeline. This weaves branch probes in the same pass as the method-entry
 * [net.bytebuddy.asm.Advice] tier, instead of using a second, competing transformer.
 *
 * Rewriting a conditional jump into two edges introduces new basic blocks. The class file's stack
 * map frames then need recomputing, so [mergeWriter] asks ByteBuddy's writer to do that.
 *
 * [probeArray] emits the load of the class's counts array at every probe.
 *
 * [probeIndexBase] is where branch slots start in the class's shared probe array. Method-entry
 * probes occupy `[0, probeIndexBase)`.
 *
 * [branchSlotCapacity] is how many branch slots the array actually has, as sized from
 * [BranchSiteAnalyzer]'s pass over the class file, less the sites of methods [SitePairing] could
 * not pair with the received bytes this rewrite walks. The rewrite must never allocate past it: a
 * site that would is left as the original instruction, uninstrumented, rather than emitting an
 * increment that would throw `ArrayIndexOutOfBoundsException` inside the application's own
 * method. A site count that differs from the analysis in either direction means the bytes being
 * rewritten do not line up with the bytes analysed, so [visitEnd] throws `IllegalStateException`
 * and the whole class is skipped and reported through the standard transform-failure path, rather
 * than being left instrumented with branch metadata that describes the wrong outcomes.
 *
 * [droppedOrdinalsByMethod] names each method's dropped sites by their per-method encounter
 * ordinal; a method absent from it, or every method when the default is left in place, has nothing
 * dropped. [throwingDefaultOrdinalsByMethod] names, in the same numbering, each method's switches
 * whose default only throws and gets no probe, and [unprobedOutcomesByMethod] each method's
 * conditionals with an outcome that gets no probe, with that outcome's offset.
 * [swappedOrdinalsByMethod] names, in the same numbering, each method's conditionals that arrive
 * testing the opposite of the class file's jump, as [SitePairing] found them; see
 * [BranchProbeMethodVisitor].
 *
 * [slotsByMethod], when given, is each method's own run of slots, relative to [probeIndexBase], so
 * a method's slots land where the analysis numbered them whatever order the rewriter meets the
 * methods in. A method it has nothing for has no slots. Each method must then ask for exactly its
 * own run's [MethodSlots.count]: a method asking for more would otherwise write into the next
 * method's slots while the class-wide total still matched, so [visitEnd] throws on the first method
 * whose count differs, the same way it throws on a class-wide difference. A site that would run
 * past its method's slots is left uninstrumented, as one past the array is. Left null, slots are
 * handed out in the order the rewriter meets the sites, checked only class-wide.
 */
class BranchProbeAsmVisitorWrapper(
    private val eligibleMethods: (name: String, descriptor: String) -> Boolean,
    private val probeArray: ProbeArrayLoad,
    private val probeIndexBase: Int,
    private val branchSlotCapacity: Int = Int.MAX_VALUE,
    private val droppedOrdinalsByMethod: (name: String, descriptor: String) -> Set<Int> = { _, _ -> emptySet() },
    private val throwingDefaultOrdinalsByMethod: (name: String, descriptor: String) -> Set<Int> = { _, _ -> emptySet() },
    private val unprobedOutcomesByMethod: (name: String, descriptor: String) -> Map<Int, Int> = { _, _ -> emptyMap() },
    private val swappedOrdinalsByMethod: (name: String, descriptor: String) -> Set<Int> = { _, _ -> emptySet() },
    private val slotsByMethod: ((name: String, descriptor: String) -> MethodSlots?)? = null,
) : AsmVisitorWrapper {
    /** One method's run of branch slots: the first at [base], relative to the branch slots' start, and [count] in all. */
    data class MethodSlots(
        val base: Int,
        val count: Int,
    )

    override fun mergeWriter(flags: Int): Int = flags or ClassWriter.COMPUTE_FRAMES

    override fun mergeReader(flags: Int): Int = flags

    override fun wrap(
        instrumentedType: TypeDescription,
        classVisitor: ClassVisitor,
        implementationContext: Implementation.Context,
        typePool: TypePool,
        fields: FieldList<FieldDescription.InDefinedShape>,
        methods: MethodList<*>,
        writerFlags: Int,
        readerFlags: Int,
    ): ClassVisitor {
        var nextSlot = 0
        var slotsWanted = 0
        val wantedByMethod = LinkedHashMap<Pair<String, String>, Int>()

        return object : ClassVisitor(Opcodes.ASM9, classVisitor) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
                if (!eligibleMethods(name, descriptor)) return delegate
                val key = name to descriptor
                // Null when slots run class-wide in encounter order; otherwise this method's own run.
                val own = slotsByMethod?.let { it(name, descriptor) ?: MethodSlots(0, 0) }
                if (own != null) wantedByMethod.putIfAbsent(key, 0)
                var nextInMethod = own?.base
                val limit = own?.let { minOf(it.base + it.count, branchSlotCapacity) } ?: branchSlotCapacity
                val allocate: (Int) -> Int = { outcomeCount ->
                    slotsWanted += outcomeCount
                    if (own != null) wantedByMethod[key] = wantedByMethod.getValue(key) + outcomeCount
                    val start = nextInMethod ?: nextSlot
                    if (start + outcomeCount > limit) {
                        BranchProbeMethodVisitor.NO_SLOT
                    } else {
                        if (nextInMethod != null) nextInMethod = start + outcomeCount else nextSlot = start + outcomeCount
                        start
                    }
                }
                return BranchProbeMethodVisitor(
                    delegate,
                    probeArray,
                    probeIndexBase,
                    droppedOrdinalsByMethod(name, descriptor),
                    throwingDefaultOrdinalsByMethod(name, descriptor),
                    unprobedOutcomesByMethod(name, descriptor),
                    swappedOrdinalsByMethod(name, descriptor),
                    allocate,
                )
            }

            override fun visitEnd() {
                for ((key, wanted) in wantedByMethod) {
                    val expected = slotsByMethod?.invoke(key.first, key.second)?.count ?: 0
                    if (wanted != expected) {
                        throw IllegalStateException(
                            "otherlode: ${instrumentedType.name}#${key.first}${key.second} wants $wanted branch probe slots at " +
                                "rewrite time but $expected were sized from its analysed bytecode; the bytes being rewritten " +
                                "differ from the bytes analysed",
                        )
                    }
                }
                if (branchSlotCapacity != Int.MAX_VALUE && slotsWanted != branchSlotCapacity) {
                    throw IllegalStateException(
                        "otherlode: ${instrumentedType.name} wants $slotsWanted branch probe slots at rewrite time but " +
                            "$branchSlotCapacity were sized from its analysed bytecode; the bytes being rewritten " +
                            "differ from the bytes analysed",
                    )
                }
                super.visitEnd()
            }
        }
    }
}
