package dev.otherlode.instrumentation.branch

import dev.otherlode.instrumentation.ProbeArrayLoad
import net.bytebuddy.jar.asm.Handle
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.commons.AnalyzerAdapter

/**
 * Splits each [ConditionalJump] into two private edges, one per outcome. It splits each
 * `TABLESWITCH`/`LOOKUPSWITCH` into one private edge per case, plus the default. Every edge
 * increments its own slot in the class's shared counts array, which [probeArray] loads:
 *
 * ```
 * IFEQ original_target              IFEQ taken
 *                            ->        probes[notTakenIndex]++
 *                                      GOTO continue
 *                                    taken:
 *                                      probes[takenIndex]++
 *                                      GOTO original_target
 *                                    continue:
 * ```
 *
 * No new edge is shared with any other control flow. So a probe only increments for the outcome
 * it stands for. This holds regardless of what the original jump target is also used for
 * elsewhere: a loop back-edge, an if/else merge point, two switch cases falling into the same
 * code, and so on. Inserting a counter directly at an original target would instead conflate
 * every outcome that shares it.
 *
 * [allocateSlots] hands out this site's slots, relative to [probeIndexBase]. Given the number of
 * outcomes the site needs, it returns the first slot, then advances its own running total by
 * that many. This lets sites of different arity (a two-outcome jump next to an N-way switch)
 * still pack into contiguous slots, in the same order
 * [dev.otherlode.instrumentation.OtherlodeInstrumentation] lays out from
 * [BranchSiteAnalyzer]'s output. It returns [NO_SLOT] when the array has no room left, and the
 * site is then emitted exactly as it was, with no probe.
 *
 * [droppedOrdinals] names sites this method's analysis dropped, by their encounter index among
 * every tracked conditional and switch in this method, counted from zero. A dropped
 * site's ordinal is still counted here, in step with [BranchSiteAnalyzer], but it is emitted
 * unchanged with no call to [allocateSlots]: a dropped site never asked for a slot in the first
 * place, so it never counts against the mismatch check [BranchProbeAsmVisitorWrapper] runs.
 *
 * [throwingDefaultOrdinals] names, in the same numbering, the switches whose default only throws
 * an exception the compiler added. Such a switch asks for one slot per case and none for its
 * default, and its default edge, fillers included, goes straight to the original default with no
 * probe.
 *
 * [unprobedOutcomes] gives, in the same numbering, the conditionals with one outcome only a hash
 * collision reaches, and that outcome's offset (see [BranchSite.unprobedOutcome]). Such a
 * conditional asks for one slot, for its other outcome, and the unprobed edge goes straight to its
 * target.
 *
 * [swappedOrdinals] names, in the same numbering, the conditionals that test the opposite of the
 * class file's jump the analysis numbered, because an earlier transformer inverted them. Every
 * offset above is the class file's: offset 0 is the class file's taken edge. For a swapped
 * conditional that is this jump's fall-through, so the two edges trade slots, and an unprobed
 * outcome trades sides with them.
 *
 * Every frame the received bytes carry is passed through as it is. A label this visitor inserts
 * gets a frame only when [frameTracker], the ASM analyser upstream of this visitor, is given, which
 * is for a class that has frames. Such a label has one predecessor, the jump or switch it sits
 * on, and the probes add no local and leave the operand stack as they found it, so the frame there
 * is the analyser's state at that instruction with the operands the jump consumes popped. The
 * continuation label sits where the original next instruction was, which may carry a frame of its
 * own. That frame covers every predecessor of the instruction, so it replaces the inserted one: the
 * inserted frame is held and written before the next instruction unless a frame arrives first.
 * A jump or switch the analyser has no state for throws [IllegalStateException] naming
 * [location], since no frame can be derived for it. A class that passes the type checker has a
 * frame after every unconditional jump, so this happens only for one that would not verify, or one
 * at version 50 that leans on the JVM's fallback to type inference.
 */
class BranchProbeMethodVisitor(
    methodVisitor: MethodVisitor,
    private val probeArray: ProbeArrayLoad,
    private val probeIndexBase: Int,
    private val droppedOrdinals: Set<Int> = emptySet(),
    private val throwingDefaultOrdinals: Set<Int> = emptySet(),
    private val unprobedOutcomes: Map<Int, Int> = emptyMap(),
    private val swappedOrdinals: Set<Int> = emptySet(),
    private val location: String = "a method",
    private val allocateSlots: (outcomeCount: Int) -> Int,
) : MethodVisitor(Opcodes.ASM9, methodVisitor) {
    private var nextOrdinal = 0

    /**
     * The analyser upstream of this visitor, set by whoever builds the chain. Null for a class
     * without frames, where no frame is written.
     */
    var frameTracker: AnalyzerAdapter? = null

    private var pendingLocals: Array<Any>? = null
    private var pendingStack: Array<Any>? = null
    private var inserted = false

    /** The frame after the instruction being visited pops [popCount] operands, or null for a class without frames. */
    private fun snapshot(popCount: Int): Frame? {
        val tracker = frameTracker ?: return null
        val locals = tracker.locals
        val stack = tracker.stack
        check(locals != null && stack != null) {
            "otherlode: no frame can be derived for a jump or switch in $location; the class's own frames do " +
                "not reach it"
        }
        return Frame(collapse(locals, locals.size), collapse(stack, stack.size - popCount))
    }

    /** The first [size] slots of [slots] with each long or double's second slot dropped, as a frame lists them. */
    private fun collapse(
        slots: List<Any>,
        size: Int,
    ): Array<Any> {
        val out = ArrayList<Any>(size)
        var i = 0
        while (i < size) {
            val type = slots[i]
            out += type
            i += if (type == Opcodes.LONG || type == Opcodes.DOUBLE) 2 else 1
        }
        return out.toTypedArray()
    }

    private fun emitFrame(frame: Frame?) {
        if (frame != null) mv.visitFrame(Opcodes.F_NEW, frame.locals.size, frame.locals, frame.stack.size, frame.stack)
    }

    private fun flushPending() {
        val locals = pendingLocals ?: return
        val stack = checkNotNull(pendingStack)
        pendingLocals = null
        pendingStack = null
        mv.visitFrame(Opcodes.F_NEW, locals.size, locals, stack.size, stack)
    }

    private fun holdFrame(frame: Frame?) {
        pendingLocals = frame?.locals
        pendingStack = frame?.stack
    }

    override fun visitFrame(
        type: Int,
        numLocal: Int,
        local: Array<out Any>?,
        numStack: Int,
        stack: Array<out Any>?,
    ) {
        // The class file's own frame at this offset covers every predecessor, the inserted edge included.
        pendingLocals = null
        pendingStack = null
        super.visitFrame(type, numLocal, local, numStack, stack)
    }

    override fun visitInsn(opcode: Int) {
        flushPending()
        super.visitInsn(opcode)
    }

    override fun visitIntInsn(
        opcode: Int,
        operand: Int,
    ) {
        flushPending()
        super.visitIntInsn(opcode, operand)
    }

    override fun visitVarInsn(
        opcode: Int,
        varIndex: Int,
    ) {
        flushPending()
        super.visitVarInsn(opcode, varIndex)
    }

    override fun visitTypeInsn(
        opcode: Int,
        type: String,
    ) {
        flushPending()
        super.visitTypeInsn(opcode, type)
    }

    override fun visitFieldInsn(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
    ) {
        flushPending()
        super.visitFieldInsn(opcode, owner, name, descriptor)
    }

    override fun visitMethodInsn(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean,
    ) {
        flushPending()
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }

    override fun visitInvokeDynamicInsn(
        name: String,
        descriptor: String,
        bootstrapMethodHandle: Handle,
        vararg bootstrapMethodArguments: Any,
    ) {
        flushPending()
        super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
    }

    override fun visitLdcInsn(value: Any) {
        flushPending()
        super.visitLdcInsn(value)
    }

    override fun visitIincInsn(
        varIndex: Int,
        increment: Int,
    ) {
        flushPending()
        super.visitIincInsn(varIndex, increment)
    }

    override fun visitMultiANewArrayInsn(
        descriptor: String,
        numDimensions: Int,
    ) {
        flushPending()
        super.visitMultiANewArrayInsn(descriptor, numDimensions)
    }

    override fun visitMaxs(
        maxStack: Int,
        maxLocals: Int,
    ) {
        // A probe holds the array and an index, the long loaded beside them, then a long constant: six slots
        // above the stack the jump leaves.
        super.visitMaxs(if (inserted) maxStack + PROBE_STACK else maxStack, maxLocals)
    }

    override fun visitJumpInsn(
        opcode: Int,
        label: Label,
    ) {
        flushPending()
        if (!ConditionalJump.isTracked(opcode)) {
            super.visitJumpInsn(opcode, label)
            return
        }
        val ordinal = nextOrdinal++
        if (ordinal in droppedOrdinals) {
            super.visitJumpInsn(opcode, label)
            return
        }
        val swapped = ordinal in swappedOrdinals
        // The analysis numbers outcomes by the class file's jump. An inverted jump's taken edge is
        // the class file's fall-through, so the unprobed side and the two slots both flip.
        val unprobed = unprobedOutcomes[ordinal]?.let { if (swapped) 1 - it else it }
        val slot = allocateSlots(if (unprobed == null) 2 else 1)
        if (slot == NO_SLOT) {
            super.visitJumpInsn(opcode, label)
            return
        }

        inserted = true
        val base = probeIndexBase + slot
        if (unprobed == 0) {
            super.visitJumpInsn(opcode, label)
            emitProbeIncrement(base)
            return
        }
        val after = snapshot(if (opcode in Opcodes.IF_ICMPEQ..Opcodes.IF_ACMPNE) 2 else 1)
        val takenSlot = if (unprobed == null && swapped) base + 1 else base
        val fallThroughSlot = if (swapped) base else base + 1
        val taken = Label()
        val continuation = Label()

        super.visitJumpInsn(opcode, taken)
        if (unprobed == null) emitProbeIncrement(fallThroughSlot)
        super.visitJumpInsn(Opcodes.GOTO, continuation)
        super.visitLabel(taken)
        emitFrame(after)
        emitProbeIncrement(takenSlot)
        super.visitJumpInsn(Opcodes.GOTO, label)
        super.visitLabel(continuation)
        holdFrame(after)
    }

    override fun visitTableSwitchInsn(
        min: Int,
        max: Int,
        dflt: Label,
        vararg labels: Label,
    ) {
        flushPending()
        val ordinal = nextOrdinal++
        if (ordinal in droppedOrdinals) {
            super.visitTableSwitchInsn(min, max, dflt, *labels)
            return
        }
        val probesDefault = ordinal !in throwingDefaultOrdinals
        val slot = allocateSlots(slotCount(dflt, labels, probesDefault))
        if (slot == NO_SLOT) {
            super.visitTableSwitchInsn(min, max, dflt, *labels)
            return
        }
        inserted = true
        val after = snapshot(1)
        val edges = SwitchEdges(dflt, labels, probesDefault)
        super.visitTableSwitchInsn(min, max, edges.newDefault, *edges.newLabels)
        emitSwitchEdges(slot, edges, after)
    }

    override fun visitLookupSwitchInsn(
        dflt: Label,
        keys: IntArray,
        labels: Array<out Label>,
    ) {
        flushPending()
        val ordinal = nextOrdinal++
        if (ordinal in droppedOrdinals) {
            super.visitLookupSwitchInsn(dflt, keys, labels)
            return
        }
        val probesDefault = ordinal !in throwingDefaultOrdinals
        val slot = allocateSlots(slotCount(dflt, labels, probesDefault))
        if (slot == NO_SLOT) {
            super.visitLookupSwitchInsn(dflt, keys, labels)
            return
        }
        inserted = true
        val after = snapshot(1)
        val edges = SwitchEdges(dflt, labels, probesDefault)
        super.visitLookupSwitchInsn(edges.newDefault, keys, edges.newLabels)
        emitSwitchEdges(slot, edges, after)
    }

    private fun slotCount(
        dflt: Label,
        labels: Array<out Label>,
        probesDefault: Boolean,
    ): Int = BranchSiteAnalyzer.switchOutcomeCount(dflt, labels) - if (probesDefault) 0 else 1

    /**
     * The private edges for one switch. Every case entry that already jumps to the default label
     * (a `TABLESWITCH` filler for a gap in the case values) is routed to the new default edge, so
     * it counts as the default outcome it is rather than as a case of its own. When
     * [probesDefault] is false, the new default edge is the original default label itself, so the
     * default and its fillers pass through with no probe.
     */
    private class SwitchEdges(
        val originalDefault: Label,
        originalLabels: Array<out Label>,
        val probesDefault: Boolean,
    ) {
        val newDefault = if (probesDefault) Label() else originalDefault
        val newLabels: Array<Label> = Array(originalLabels.size) { i -> if (originalLabels[i] === originalDefault) newDefault else Label() }
        val caseIndices: List<Int> = originalLabels.indices.filter { originalLabels[it] !== originalDefault }
        val caseTargets: List<Label> = caseIndices.map { originalLabels[it] }
        val caseEdges: List<Label> = caseIndices.map { newLabels[it] }
    }

    private fun emitSwitchEdges(
        slot: Int,
        edges: SwitchEdges,
        after: Frame?,
    ) {
        val base = probeIndexBase + slot
        edges.caseEdges.forEachIndexed { i, block ->
            super.visitLabel(block)
            emitFrame(after)
            emitProbeIncrement(base + i)
            super.visitJumpInsn(Opcodes.GOTO, edges.caseTargets[i])
        }
        if (!edges.probesDefault) return
        super.visitLabel(edges.newDefault)
        emitFrame(after)
        emitProbeIncrement(base + edges.caseEdges.size)
        super.visitJumpInsn(Opcodes.GOTO, edges.originalDefault)
    }

    private fun emitProbeIncrement(index: Int) {
        probeArray.load(mv)
        pushInt(index)
        super.visitInsn(Opcodes.DUP2)
        super.visitInsn(Opcodes.LALOAD)
        super.visitInsn(Opcodes.LCONST_1)
        super.visitInsn(Opcodes.LADD)
        super.visitInsn(Opcodes.LASTORE)
    }

    private fun pushInt(value: Int) {
        when (value) {
            in -1..5 -> super.visitInsn(Opcodes.ICONST_0 + value)
            in Byte.MIN_VALUE..Byte.MAX_VALUE -> super.visitIntInsn(Opcodes.BIPUSH, value)
            in Short.MIN_VALUE..Short.MAX_VALUE -> super.visitIntInsn(Opcodes.SIPUSH, value)
            else -> super.visitLdcInsn(value)
        }
    }

    /** An expanded frame: its locals and operand stack, long and double entries counted once. */
    private class Frame(
        val locals: Array<Any>,
        val stack: Array<Any>,
    )

    companion object {
        /** Returned by an allocator that has no slots left for a site. */
        const val NO_SLOT = -1

        private const val PROBE_STACK = 6
    }
}
