package dev.otherlode.instrumentation.branch

import dev.otherlode.export.LineRange
import dev.otherlode.export.RoutineKind
import net.bytebuddy.jar.asm.Handle
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes

internal class InstructionRecorder(
    downstream: (InstructionRecorder) -> MethodVisitor,
    private val onTracked: (IntArray) -> Unit = {},
    private val onEnd: (() -> MethodInstructions) -> Unit,
) : MethodVisitor(Opcodes.ASM9) {
    var lastOrdinal = -1
        private set

    private var opcodes = IntArray(INITIAL_CAPACITY)
    private var lines = IntArray(INITIAL_CAPACITY)
    private var currentLine = -1

    private var branchTargets = arrayOfNulls<Any>(INITIAL_CAPACITY)

    private var operands = arrayOfNulls<Any>(INITIAL_CAPACITY)
    private val tryCatchBlocks = mutableListOf<TryCatchLabels>()
    private val localVariables = mutableListOf<LocalVariableLabels>()
    private var hasSubroutine = false
    private var tracked = NO_INTS
    private var trackedSize = 0

    init {
        mv = downstream(this)
    }

    private fun track(value: Int) {
        if (trackedSize == tracked.size) tracked = tracked.copyOf(maxOf(TRACKED_CAPACITY, trackedSize * 2))
        tracked[trackedSize++] = value
    }

    private fun record(opcode: Int) {
        val ordinal = lastOrdinal + 1
        if (ordinal == opcodes.size) {
            opcodes = opcodes.copyOf(ordinal * 2)
            lines = lines.copyOf(ordinal * 2)
            branchTargets = branchTargets.copyOf(ordinal * 2)
            operands = operands.copyOf(ordinal * 2)
        }
        opcodes[ordinal] = opcode
        lines[ordinal] = currentLine
        lastOrdinal = ordinal
    }

    // The analyser's ClassReader pass makes its own labels and nothing else reads their `info`
    // field, so it holds each label's ordinal.
    override fun visitLabel(label: Label) {
        label.info = lastOrdinal + 1
        super.visitLabel(label)
    }

    override fun visitLineNumber(
        line: Int,
        start: Label,
    ) {
        currentLine = line
        super.visitLineNumber(line, start)
    }

    override fun visitTryCatchBlock(
        start: Label,
        end: Label,
        handler: Label,
        type: String?,
    ) {
        tryCatchBlocks += TryCatchLabels(start, end, handler, type)
        super.visitTryCatchBlock(start, end, handler, type)
    }

    override fun visitLocalVariable(
        name: String,
        descriptor: String,
        signature: String?,
        start: Label,
        end: Label,
        index: Int,
    ) {
        localVariables += LocalVariableLabels(name, start, end, index)
        super.visitLocalVariable(name, descriptor, signature, start, end, index)
    }

    override fun visitInsn(opcode: Int) {
        record(opcode)
        super.visitInsn(opcode)
    }

    override fun visitIntInsn(
        opcode: Int,
        operand: Int,
    ) {
        record(opcode)
        operands[lastOrdinal] = Constant(operand)
        super.visitIntInsn(opcode, operand)
    }

    override fun visitVarInsn(
        opcode: Int,
        varIndex: Int,
    ) {
        if (opcode == Opcodes.RET) hasSubroutine = true
        record(opcode)
        operands[lastOrdinal] = varIndex
        super.visitVarInsn(opcode, varIndex)
    }

    override fun visitTypeInsn(
        opcode: Int,
        type: String,
    ) {
        record(opcode)
        operands[lastOrdinal] = type
        super.visitTypeInsn(opcode, type)
    }

    override fun visitFieldInsn(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
    ) {
        record(opcode)
        operands[lastOrdinal] = FieldOperand(owner, name, descriptor)
        super.visitFieldInsn(opcode, owner, name, descriptor)
    }

    override fun visitMethodInsn(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean,
    ) {
        record(opcode)
        operands[lastOrdinal] = CalledMethod(owner, name, descriptor)
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }

    override fun visitInvokeDynamicInsn(
        name: String,
        descriptor: String,
        bootstrapMethodHandle: Handle,
        vararg bootstrapMethodArguments: Any,
    ) {
        record(Opcodes.INVOKEDYNAMIC)
        operands[lastOrdinal] = CalledMethod(bootstrapMethodHandle.owner, bootstrapMethodHandle.name, descriptor)
        super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
    }

    override fun visitJumpInsn(
        opcode: Int,
        label: Label,
    ) {
        if (opcode == Opcodes.JSR) hasSubroutine = true
        if (ConditionalJump.isTracked(opcode)) track(opcode)
        record(opcode)
        branchTargets[lastOrdinal] = label
        super.visitJumpInsn(opcode, label)
    }

    override fun visitLdcInsn(value: Any?) {
        record(Opcodes.LDC)
        operands[lastOrdinal] = Constant(value)
        super.visitLdcInsn(value)
    }

    override fun visitIincInsn(
        varIndex: Int,
        increment: Int,
    ) {
        record(Opcodes.IINC)
        operands[lastOrdinal] = Increment(varIndex, increment)
        super.visitIincInsn(varIndex, increment)
    }

    override fun visitTableSwitchInsn(
        min: Int,
        max: Int,
        dflt: Label,
        vararg labels: Label,
    ) {
        track(Opcodes.TABLESWITCH)
        track(min)
        track(max)
        track(labels.size)
        for (label in labels) track(if (label === dflt) 1 else 0)
        record(Opcodes.TABLESWITCH)
        branchTargets[lastOrdinal] = SwitchLabels(dflt, labels)
        super.visitTableSwitchInsn(min, max, dflt, *labels)
    }

    override fun visitLookupSwitchInsn(
        dflt: Label,
        keys: IntArray,
        labels: Array<out Label>,
    ) {
        track(Opcodes.LOOKUPSWITCH)
        track(keys.size)
        for (key in keys) track(key)
        for (label in labels) track(if (label === dflt) 1 else 0)
        record(Opcodes.LOOKUPSWITCH)
        branchTargets[lastOrdinal] = SwitchLabels(dflt, labels)
        super.visitLookupSwitchInsn(dflt, keys, labels)
    }

    override fun visitMultiANewArrayInsn(
        descriptor: String,
        numDimensions: Int,
    ) {
        record(Opcodes.MULTIANEWARRAY)
        super.visitMultiANewArrayInsn(descriptor, numDimensions)
    }

    override fun visitEnd() {
        super.visitEnd()
        onTracked(if (trackedSize == 0) NO_INTS else tracked.copyOf(trackedSize))
        onEnd(::instructions)
    }

    private fun instructions(): MethodInstructions {
        val size = lastOrdinal + 1

        fun visitedOrdinal(label: Label): Int? = label.info as? Int

        fun ordinalOf(label: Label): Int = visitedOrdinal(label)?.takeIf { it < size } ?: -1
        val targets = arrayOfNulls<IntArray>(size)
        val caseDefaults = arrayOfNulls<BooleanArray>(size)
        for (ordinal in 0 until size) {
            when (val target = branchTargets[ordinal]) {
                is Label -> {
                    targets[ordinal] = intArrayOf(ordinalOf(target))
                }

                is SwitchLabels -> {
                    targets[ordinal] =
                        IntArray(target.labels.size + 1) { if (it == 0) ordinalOf(target.dflt) else ordinalOf(target.labels[it - 1]) }
                    caseDefaults[ordinal] = BooleanArray(target.labels.size) { target.labels[it] === target.dflt }
                }
            }
        }
        val tryCatches =
            tryCatchBlocks.mapNotNull { block ->
                val start = visitedOrdinal(block.start) ?: return@mapNotNull null
                val end = visitedOrdinal(block.end) ?: return@mapNotNull null
                val handler = ordinalOf(block.handler).takeIf { it >= 0 } ?: return@mapNotNull null
                TryCatch(start, end.coerceAtMost(size), handler, block.type)
            }
        val locals =
            localVariables.mapNotNull { local ->
                val start = visitedOrdinal(local.start) ?: return@mapNotNull null
                val end = visitedOrdinal(local.end) ?: return@mapNotNull null
                LocalRange(local.name, start, end, local.index)
            }
        return MethodInstructions(
            opcodes.copyOf(size),
            lines.copyOf(size),
            targets,
            caseDefaults,
            tryCatches,
            hasSubroutine,
            operands.copyOf(size),
            locals,
        )
    }

    private class LocalVariableLabels(
        val name: String,
        val start: Label,
        val end: Label,
        val index: Int,
    )

    private class SwitchLabels(
        val dflt: Label,
        val labels: Array<out Label>,
    )

    private class TryCatchLabels(
        val start: Label,
        val end: Label,
        val handler: Label,
        val type: String?,
    )

    private companion object {
        const val INITIAL_CAPACITY = 64
        const val TRACKED_CAPACITY = 8
        val NO_INTS = IntArray(0)
    }
}

internal class TryCatch(
    val start: Int,
    val end: Int,
    val handler: Int,
    val type: String? = null,
)

internal data class CalledMethod(
    val owner: String,
    val name: String,
    val descriptor: String,
)

internal data class LocalRange(
    val name: String,
    val start: Int,
    val end: Int,
    val index: Int,
)

internal data class FieldOperand(
    val owner: String,
    val name: String,
    val descriptor: String,
)

internal data class Constant(
    val value: Any?,
)

internal data class Increment(
    val varIndex: Int,
    val amount: Int,
)

internal class MethodInstructions(
    val opcodes: IntArray,
    val lines: IntArray,
    val targets: Array<IntArray?>,
    val caseDefaults: Array<BooleanArray?>,
    val tryCatches: List<TryCatch>,
    val hasSubroutine: Boolean,
    val operands: Array<Any?> = arrayOfNulls(opcodes.size),
    private val localRanges: List<LocalRange> = emptyList(),
) {
    val size: Int get() = opcodes.size

    fun localName(
        ordinal: Int,
        slot: Int,
    ): String? =
        localRanges
            .firstOrNull { it.index == slot && (ordinal in it.start until it.end || ordinal + 1 in it.start until it.end) }
            ?.name

    fun isTrackedSite(ordinal: Int): Boolean {
        val opcode = opcodes[ordinal]
        return ConditionalJump.isTracked(opcode) || opcode == Opcodes.TABLESWITCH || opcode == Opcodes.LOOKUPSWITCH
    }
}

internal data class SourceLine(
    val sourceFile: String,
    val line: Int,
)

/**
 * What [GuardAnalysis] finds for one kept site: its [guard], and per outcome offset its guarded and
 * partly guarded line ranges and its routine kind.
 */
class SiteGuards internal constructor(
    val guard: Int?,
    val guardedLines: List<List<LineRange>>,
    val partlyGuardedLines: List<List<LineRange>>,
    val routineKinds: List<RoutineKind> = emptyList(),
)

internal class MethodGuards(
    val sites: Map<Int, SiteGuards>,
    private val instructionGuards: IntArray,
) {
    fun guardAt(ordinal: Int): Int? = instructionGuards.getOrNull(ordinal)?.takeIf { it >= 0 }
}

internal object GuardAnalysis {
    private val NO_SUCCESSORS = IntArray(0)

    fun analyze(
        instructions: MethodInstructions,
        sites: List<BranchSite>,
        firstBranchIndexes: IntArray,
        sourceLineOf: (Int) -> SourceLine?,
        isThrowable: (internalName: String) -> Boolean = { false },
    ): MethodGuards? {
        if (instructions.hasSubroutine) return null
        val realCount = instructions.size
        if (realCount == 0) return null
        val siteOrdinals = IntArray(sites.size)
        var found = 0
        for (ordinal in 0 until realCount) {
            if (!instructions.isTrackedSite(ordinal)) continue
            if (found == sites.size) return null
            siteOrdinals[found++] = ordinal
        }
        if (found != sites.size) return null

        val graph = Graph(instructions, sites, siteOrdinals, firstBranchIndexes)
        val dominance = Dominance(graph.successors, entry = 0, firstOutcomeNode = realCount)

        val instructionGuards = IntArray(realCount) { graph.branchIndexOf(dominance.outcomeAbove(it)) }
        val lines = LineTable(instructions, realCount, dominance, sourceLineOf)
        val routine = RoutineClassifier(instructions, graph, dominance, isThrowable)

        val siteGuards = HashMap<Int, SiteGuards>()
        for ((position, site) in sites.withIndex()) {
            if (site.dropReason != null) continue
            val firstNode = graph.firstOutcomeNode[position]
            val guarded = mutableListOf<List<LineRange>>()
            val partlyGuarded = mutableListOf<List<LineRange>>()
            for (probed in 0 until site.probedOutcomeCount) {
                val (whole, part) = lines.guardedBy(firstNode + probed, dominance)
                guarded += whole
                partlyGuarded += part
            }
            val guard = instructionGuards[siteOrdinals[position]].takeIf { it >= 0 }
            val routineKinds =
                site.probedPositions.mapIndexed {
                    probed,
                    offset,
                    ->
                    routine.kindOf(siteOrdinals[position], firstNode + probed, offset)
                }
            siteGuards[site.siteIndex] = SiteGuards(guard, guarded, partlyGuarded, routineKinds)
        }
        return MethodGuards(siteGuards, instructionGuards)
    }

    internal class Graph(
        instructions: MethodInstructions,
        sites: List<BranchSite>,
        siteOrdinals: IntArray,
        firstBranchIndexes: IntArray,
    ) {
        val realCount = instructions.size

        val firstOutcomeNode = IntArray(sites.size) { -1 }

        val successors: Array<IntArray>

        val normalSuccessors: Array<IntArray>

        private val outcomeBranchIndexes: IntArray

        init {
            var nodeCount = realCount
            for ((position, site) in sites.withIndex()) {
                if (site.dropReason == null) {
                    firstOutcomeNode[position] = nodeCount
                    nodeCount += site.probedOutcomeCount
                }
            }
            outcomeBranchIndexes = IntArray(nodeCount - realCount)
            val edges = arrayOfNulls<IntArray>(nodeCount)
            val sitePositionByOrdinal = IntArray(realCount) { -1 }
            siteOrdinals.forEachIndexed { position, ordinal -> sitePositionByOrdinal[ordinal] = position }

            for (ordinal in 0 until realCount) {
                val position = sitePositionByOrdinal[ordinal]
                edges[ordinal] =
                    if (position < 0) {
                        plainSuccessors(instructions, ordinal)
                    } else {
                        siteSuccessors(instructions, ordinal, sites[position], position, firstBranchIndexes[position], edges)
                    }
            }
            normalSuccessors = Array(nodeCount) { edges[it] ?: NO_SUCCESSORS }
            for (tryCatch in instructions.tryCatches) {
                for (ordinal in tryCatch.start until tryCatch.end) edges[ordinal] = edges[ordinal]!! + tryCatch.handler
            }
            successors = Array(nodeCount) { edges[it] ?: NO_SUCCESSORS }
        }

        fun branchIndexOf(node: Int): Int = if (node < 0) -1 else outcomeBranchIndexes[node - realCount]

        private fun plainSuccessors(
            instructions: MethodInstructions,
            ordinal: Int,
        ): IntArray {
            val opcode = instructions.opcodes[ordinal]
            val next = if (ordinal + 1 < realCount) ordinal + 1 else -1
            val targets = instructions.targets[ordinal]
            return when {
                opcode == Opcodes.GOTO || opcode == Opcodes.TABLESWITCH || opcode == Opcodes.LOOKUPSWITCH -> validDistinct(targets!!)
                ConditionalJump.isTracked(opcode) -> validDistinct(intArrayOf(targets!![0], next))
                opcode == Opcodes.ATHROW || opcode in Opcodes.IRETURN..Opcodes.RETURN -> NO_SUCCESSORS
                next >= 0 -> intArrayOf(next)
                else -> NO_SUCCESSORS
            }
        }

        private fun validDistinct(nodes: IntArray): IntArray = nodes.filter { it >= 0 }.distinct().toIntArray()

        private fun siteSuccessors(
            instructions: MethodInstructions,
            ordinal: Int,
            site: BranchSite,
            position: Int,
            firstBranchIndex: Int,
            edges: Array<IntArray?>,
        ): IntArray {
            val targets = instructions.targets[ordinal]!!
            val caseDefaults = instructions.caseDefaults[ordinal]
            if (site.dropReason == BranchDropReason.COROUTINE_MACHINERY && caseDefaults != null) {
                return validDistinct(intArrayOf(targets[1]))
            }
            if (site.dropReason != null) return plainSuccessors(instructions, ordinal)

            val firstNode = firstOutcomeNode[position]
            val outcomeTargets =
                if (caseDefaults == null) {
                    intArrayOf(targets[0], if (ordinal + 1 < realCount) ordinal + 1 else -1)
                } else {
                    val cases = (1 until targets.size).filterNot { caseDefaults[it - 1] }.map { targets[it] }
                    (cases + targets[0]).toIntArray()
                }
            val probedPositions = site.probedPositions
            probedPositions.forEachIndexed { probed, offset ->
                val node = firstNode + probed
                outcomeBranchIndexes[node - realCount] = firstBranchIndex + offset
                val target = outcomeTargets.getOrElse(offset) { -1 }
                edges[node] = if (target >= 0) intArrayOf(target) else NO_SUCCESSORS
            }
            val outcomeNodes = IntArray(probedPositions.size) { firstNode + it }
            val unprobedTargets = outcomeTargets.indices.filter { it !in probedPositions }.map { outcomeTargets[it] }
            if (unprobedTargets.isEmpty()) return outcomeNodes
            return validDistinct(outcomeNodes + unprobedTargets)
        }
    }

    internal class Dominance(
        successors: Array<IntArray>,
        entry: Int,
        firstOutcomeNode: Int,
    ) {
        private val nodeCount = successors.size
        private val postIndex = IntArray(nodeCount) { -1 }
        private val idom = IntArray(nodeCount) { -1 }
        private val nearestOutcome = IntArray(nodeCount) { -1 }

        val preorder: IntArray

        val preorderIndex = IntArray(nodeCount) { -1 }

        val subtreeSize = IntArray(nodeCount)

        init {
            val postorder = postorder(successors, entry)
            val predecessors = predecessors(successors, postorder)
            idom[entry] = entry
            var changed = true
            while (changed) {
                changed = false
                for (i in postorder.size - 2 downTo 0) {
                    val node = postorder[i]
                    var candidate = -1
                    for (predecessor in predecessors[node]) {
                        if (idom[predecessor] == -1) continue
                        candidate = if (candidate == -1) predecessor else intersect(predecessor, candidate)
                    }
                    if (candidate != -1 && idom[node] != candidate) {
                        idom[node] = candidate
                        changed = true
                    }
                }
            }
            preorder = treePreorder(postorder, entry)
            for (i in 1 until preorder.size) {
                val node = preorder[i]
                val parent = idom[node]
                nearestOutcome[node] = if (parent >= firstOutcomeNode) parent else nearestOutcome[parent]
            }
        }

        fun isReachable(node: Int): Boolean = postIndex[node] >= 0

        fun dominates(
            dominator: Int,
            node: Int,
        ): Boolean {
            val start = preorderIndex[dominator]
            val index = preorderIndex[node]
            return start >= 0 && index >= start && index < start + subtreeSize[dominator]
        }

        fun outcomeAbove(node: Int): Int = nearestOutcome[node]

        private fun intersect(
            first: Int,
            second: Int,
        ): Int {
            var a = first
            var b = second
            while (a != b) {
                while (postIndex[a] < postIndex[b]) a = idom[a]
                while (postIndex[b] < postIndex[a]) b = idom[b]
            }
            return a
        }

        private fun postorder(
            successors: Array<IntArray>,
            entry: Int,
        ): IntArray {
            val order = IntArray(nodeCount)
            var count = 0
            val visited = BooleanArray(nodeCount)
            val stack = IntArray(nodeCount)
            val nextChild = IntArray(nodeCount)
            var top = 0
            stack[0] = entry
            visited[entry] = true
            while (top >= 0) {
                val node = stack[top]
                val children = successors[node]
                if (nextChild[node] < children.size) {
                    val child = children[nextChild[node]++]
                    if (!visited[child]) {
                        visited[child] = true
                        stack[++top] = child
                    }
                } else {
                    top--
                    postIndex[node] = count
                    order[count++] = node
                }
            }
            return order.copyOf(count)
        }

        private fun predecessors(
            successors: Array<IntArray>,
            postorder: IntArray,
        ): Array<IntArray> {
            val counts = IntArray(nodeCount)
            for (node in postorder) for (child in successors[node]) counts[child]++
            val result = Array(nodeCount) { IntArray(counts[it]) }
            val filled = IntArray(nodeCount)
            for (node in postorder) for (child in successors[node]) result[child][filled[child]++] = node
            return result
        }

        private fun treePreorder(
            postorder: IntArray,
            entry: Int,
        ): IntArray {
            val childCounts = IntArray(nodeCount)
            for (node in postorder) if (node != entry) childCounts[idom[node]]++
            val children = Array(nodeCount) { IntArray(childCounts[it]) }
            val filled = IntArray(nodeCount)
            for (i in postorder.size - 1 downTo 0) {
                val node = postorder[i]
                if (node != entry) children[idom[node]][filled[idom[node]]++] = node
            }
            val order = IntArray(postorder.size)
            var count = 0
            val stack = IntArray(postorder.size)
            var top = 0
            stack[0] = entry
            while (top >= 0) {
                val node = stack[top--]
                preorderIndex[node] = count
                order[count++] = node
                val nodeChildren = children[node]
                for (i in nodeChildren.size - 1 downTo 0) stack[++top] = nodeChildren[i]
            }
            for (i in count - 1 downTo 0) {
                val node = order[i]
                subtreeSize[node] += 1
                if (node != entry) subtreeSize[idom[node]] += subtreeSize[node]
            }
            return order
        }
    }

    private class LineTable(
        instructions: MethodInstructions,
        private val realCount: Int,
        dominance: Dominance,
        sourceLineOf: (Int) -> SourceLine?,
    ) {
        private val keys = mutableListOf<SourceLine>()
        private val keyOfInstruction = IntArray(realCount) { -1 }

        private val codeLinesByFile: Map<String, java.util.SortedSet<Int>>
        private val totals: IntArray
        private val scratch: IntArray

        init {
            val idsByOutputLine = HashMap<Int, Int>()
            val idsByKey = HashMap<SourceLine, Int>()
            for (ordinal in 0 until realCount) {
                if (!dominance.isReachable(ordinal)) continue
                val outputLine = instructions.lines[ordinal]
                if (outputLine < 0) continue
                val id =
                    idsByOutputLine.getOrPut(outputLine) {
                        val key = sourceLineOf(outputLine)
                        if (key == null) -1 else idsByKey.getOrPut(key) { keys.size.also { keys += key } }
                    }
                keyOfInstruction[ordinal] = id
            }
            codeLinesByFile = keys.groupBy({ it.sourceFile }, { it.line }).mapValues { (_, lines) -> lines.toSortedSet() }
            totals = IntArray(keys.size)
            for (id in keyOfInstruction) if (id >= 0) totals[id]++
            scratch = IntArray(keys.size)
        }

        fun guardedBy(
            outcomeNode: Int,
            dominance: Dominance,
        ): Pair<List<LineRange>, List<LineRange>> {
            val start = dominance.preorderIndex[outcomeNode]
            if (start < 0 || keys.isEmpty()) return EMPTY
            val touched = mutableListOf<Int>()
            for (i in start until start + dominance.subtreeSize[outcomeNode]) {
                val node = dominance.preorder[i]
                if (node >= realCount) continue
                val id = keyOfInstruction[node]
                if (id < 0) continue
                if (scratch[id]++ == 0) touched += id
            }
            if (touched.isEmpty()) return EMPTY
            val whole = mutableListOf<SourceLine>()
            val part = mutableListOf<SourceLine>()
            for (id in touched) {
                if (scratch[id] == totals[id]) whole += keys[id] else part += keys[id]
                scratch[id] = 0
            }
            return ranges(whole) to ranges(part)
        }

        private fun ranges(lines: List<SourceLine>): List<LineRange> {
            if (lines.isEmpty()) return emptyList()
            val sorted = lines.sortedWith(compareBy({ it.sourceFile }, { it.line }))
            val result = mutableListOf<LineRange>()
            var first = sorted[0]
            var last = first.line
            for (i in 1 until sorted.size) {
                val line = sorted[i]
                if (line.sourceFile == first.sourceFile && noCodeBetween(line.sourceFile, last, line.line)) {
                    last = line.line
                } else {
                    result += LineRange(first.sourceFile, first.line, last)
                    first = line
                    last = line.line
                }
            }
            result += LineRange(first.sourceFile, first.line, last)
            return result
        }

        private fun noCodeBetween(
            sourceFile: String,
            below: Int,
            above: Int,
        ): Boolean = above <= below + 1 || codeLinesByFile[sourceFile]?.subSet(below + 1, above).isNullOrEmpty()

        private companion object {
            val EMPTY: Pair<List<LineRange>, List<LineRange>> = emptyList<LineRange>() to emptyList()
        }
    }
}
