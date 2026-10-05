package dev.otherlode.instrumentation.branch

import dev.otherlode.instrumentation.ProbeArrayLoad
import net.bytebuddy.asm.AsmVisitorWrapper
import net.bytebuddy.description.field.FieldDescription
import net.bytebuddy.description.field.FieldList
import net.bytebuddy.description.method.MethodList
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.implementation.Implementation
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.commons.AnalyzerAdapter
import net.bytebuddy.pool.TypePool

/**
 * Hosts the branch-tracking tier's raw ASM rewrite inside ByteBuddy's own class-transform
 * pipeline. This weaves branch probes in the same pass as the method-entry probes,
 * instead of using a second, competing transformer.
 *
 * Rewriting a conditional jump into two edges introduces new basic blocks, each needing a stack map
 * frame. No frame is ever computed. [mergeReader] asks for expanded frames, every frame the received
 * bytes carry is passed through as it is, and [BranchProbeMethodVisitor] writes a frame at each label
 * it inserts from the state of an ASM `AnalyzerAdapter` placed in front of it. The branch tier
 * writes no frame in a class below version 50, nor in one at version 50 without a StackMapTable
 * ([classHasFrames]): the analyser has no state after an unconditional jump in a class without
 * frames, and the JVM verifies such a class by type inference. The probe-array accessor writes one
 * frame at every version below 55, which ASM writes as a `StackMap` attribute below 50 that HotSpot ignores.
 * The writer is not asked to compute maximums either: [BranchProbeMethodVisitor.visitMaxs] adds the
 * probes' peak of six operand stack slots to a method it put a probe in.
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
    private val classHasFrames: Boolean = true,
) : AsmVisitorWrapper {
    /** One method's run of branch slots: the first at [base], relative to the branch slots' start, and [count] in all. */
    data class MethodSlots(
        val base: Int,
        val count: Int,
    )

    companion object {
        private const val MAJOR_VERSION_MASK = 0xffff
        private const val CLASS_VERSION_OFFSET = 6

        /**
         * Whether the frames of [classFile] are to be kept and extended: false only for a class at
         * version 50 with no stack map frame in any method, which the JVM verifies by type
         * inference. Every other class, unreadable bytes and null included, answers true; below 50
         * there is no frame to write whatever this says.
         */
        fun carriesFrames(classFile: ByteArray?): Boolean {
            if (classFile == null || classFile.size < CLASS_VERSION_OFFSET + 2) return true
            val major = ((classFile[CLASS_VERSION_OFFSET].toInt() and 0xff) shl 8) or (classFile[CLASS_VERSION_OFFSET + 1].toInt() and 0xff)
            if (major != Opcodes.V1_6) return true
            var found = false
            try {
                ClassReader(classFile).accept(
                    object : ClassVisitor(Opcodes.ASM9) {
                        override fun visitMethod(
                            access: Int,
                            name: String,
                            descriptor: String,
                            signature: String?,
                            exceptions: Array<out String>?,
                        ): MethodVisitor =
                            object : MethodVisitor(Opcodes.ASM9) {
                                override fun visitFrame(
                                    type: Int,
                                    numLocal: Int,
                                    local: Array<out Any>?,
                                    numStack: Int,
                                    stack: Array<out Any>?,
                                ) {
                                    found = true
                                }
                            }
                    },
                    0,
                )
            } catch (_: RuntimeException) {
                return true
            }
            return found
        }
    }

    override fun mergeWriter(flags: Int): Int = flags

    override fun mergeReader(flags: Int): Int = flags or ClassReader.EXPAND_FRAMES

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
            private var owner: String = instrumentedType.internalName
            private var withFrames = classHasFrames

            override fun visit(
                version: Int,
                access: Int,
                name: String,
                signature: String?,
                superName: String?,
                interfaces: Array<out String>?,
            ) {
                owner = name
                val major = version and MAJOR_VERSION_MASK
                withFrames = major >= Opcodes.V1_7 || (major == Opcodes.V1_6 && classHasFrames)
                super.visit(version, access, name, signature, superName, interfaces)
            }

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
                val probes =
                    BranchProbeMethodVisitor(
                        delegate,
                        probeArray,
                        probeIndexBase,
                        droppedOrdinalsByMethod(name, descriptor),
                        throwingDefaultOrdinalsByMethod(name, descriptor),
                        unprobedOutcomesByMethod(name, descriptor),
                        swappedOrdinalsByMethod(name, descriptor),
                        location = "${instrumentedType.name}#$name$descriptor",
                        allocateSlots = allocate,
                    )
                if (!withFrames) return probes
                val analyzer = AnalyzerAdapter(owner, access, name, descriptor, probes)
                probes.frameTracker = analyzer
                return analyzer
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
