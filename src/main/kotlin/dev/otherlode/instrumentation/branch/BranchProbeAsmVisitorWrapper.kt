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
 * pipeline, so branch probes are woven in the same pass as the method-entry probes.
 *
 * No frame is computed: every frame the received bytes carry passes through, and
 * [BranchProbeMethodVisitor] writes one at each label it inserts. A class below version 50, or at
 * version 50 without a StackMapTable ([classHasFrames] false), gets no branch frames.
 *
 * @property probeIndexBase Where branch slots start in the class's shared probe array. Method-entry
 * probes occupy `[0, probeIndexBase)`.
 * @property branchSlotCapacity How many branch slots the array has. The default, `Int.MAX_VALUE`, means
 * unbounded and unchecked. A site that would allocate past it is left uninstrumented. When a capacity
 * is given and the slots the rewrite wants differ from it, [visitEnd] throws `IllegalStateException`
 * and the class is skipped through the transform-failure path.
 * @property droppedOrdinalsByMethod Each method's dropped sites by their per-method encounter ordinal.
 * @property throwingDefaultOrdinalsByMethod In the same numbering, each method's switches whose default only
 * throws and gets no probe.
 * @property unprobedOutcomesByMethod Each method's conditionals with an outcome that gets no probe, with that
 * outcome's offset.
 * @property swappedOrdinalsByMethod In the same numbering, each method's conditionals that arrive
 * testing the opposite of the class file's jump, as [SitePairing] found them.
 * @property slotsByMethod When given, each method's own run of slots, relative to [probeIndexBase]. A
 * method it returns null for has no slots, and a site that would run past its method's run is left
 * uninstrumented.
 * [visitEnd] throws `IllegalStateException` for the first method whose wanted count differs from
 * its run's [MethodSlots.count]. When null, slots go out in encounter order and are checked class-wide only.
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
    /**
     * One method's run of branch slots: the first at [base], relative to the branch slots' start, and
     * [count] in all.
     */
    data class MethodSlots(
        val base: Int,
        val count: Int,
    )

    companion object {
        private const val MAJOR_VERSION_MASK = 0xffff
        private const val CLASS_VERSION_OFFSET = 6

        /**
         * Whether the frames of [classFile] are to be kept and extended: false only for a class at
         * version 50 with no stack map frame in any method. Every other class, unreadable bytes and
         * null included, answers true.
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
