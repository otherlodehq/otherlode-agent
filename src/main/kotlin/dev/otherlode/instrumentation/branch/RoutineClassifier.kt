package dev.otherlode.instrumentation.branch

import dev.otherlode.export.RoutineKind
import net.bytebuddy.jar.asm.Opcodes

internal class RoutineClassifier(
    private val instructions: MethodInstructions,
    private val graph: GuardAnalysis.Graph,
    private val dominance: GuardAnalysis.Dominance,
    private val isThrowable: (internalName: String) -> Boolean,
) {
    private val realCount = instructions.size

    private val finallyHandlers: List<Int> by lazy { findFinallyHandlers() }

    private val handlingCatches: List<TryCatch> by lazy {
        instructions.tryCatches.filter { it.type != null && !alwaysThrows(it.handler) }
    }

    private val jumpTargets: Set<Int> by lazy {
        instructions.targets.flatMapTo(HashSet()) { it?.asIterable() ?: emptyList() }
    }

    fun kindOf(
        siteOrdinal: Int,
        outcomeNode: Int,
        offset: Int,
    ): RoutineKind {
        if (inFinallyHandler(siteOrdinal) && hasNormalPathTwin(siteOrdinal)) return RoutineKind.FINALLY_COPY
        if (!dominance.isReachable(outcomeNode)) return RoutineKind.NONE
        val path = walk(outcomeNode, isNullSide = nullSide(siteOrdinal) == offset)
        return when {
            path.throwOnly -> RoutineKind.THROW_ONLY
            path.nullDefault -> RoutineKind.NULL_DEFAULT
            else -> RoutineKind.NONE
        }
    }

    private fun inFinallyHandler(ordinal: Int): Boolean = finallyHandlers.any { dominance.dominates(it, ordinal) }

    private fun hasNormalPathTwin(ordinal: Int): Boolean {
        val opcode = instructions.opcodes[ordinal]
        val line = instructions.lines[ordinal]
        return (0 until realCount).any { other ->
            other != ordinal &&
                instructions.isTrackedSite(other) &&
                instructions.opcodes[other] == opcode &&
                (line < 0 || instructions.lines[other] == line) &&
                sameLeadUp(ordinal, other) &&
                !inFinallyHandler(other)
        }
    }

    private fun sameLeadUp(
        first: Int,
        second: Int,
    ): Boolean {
        val copies = copyStarts(first, second)
        val pairedSlots = HashMap<Int, Int>()
        val pairedBack = HashMap<Int, Int>()
        for (back in 1..LEAD_UP) {
            val a = first - back
            val b = second - back
            if (a < 0 || b < 0 || endsStatement(a) || endsStatement(b)) return true
            if (instructions.opcodes[a] != instructions.opcodes[b] || !sameOperand(a, b, copies, pairedSlots, pairedBack)) {
                return false
            }
        }
        return true
    }

    private class CopyStarts(
        val exception: Int,
        val normal: Int,
    )

    private fun copyStarts(
        site: Int,
        twin: Int,
    ): CopyStarts {
        val handler = finallyHandlers.filter { dominance.dominates(it, site) }.maxOrNull() ?: return CopyStarts(site, -1)
        val normal =
            instructions.tryCatches
                .filter { it.type == null && it.handler == handler && it.end <= twin }
                .maxOfOrNull { it.end } ?: -1
        return CopyStarts(handler + 1, normal)
    }

    private fun storesTo(
        slot: Int,
        from: Int,
        to: Int,
    ): Boolean =
        from in 0..to &&
            (from until to).any { instructions.opcodes[it] in Opcodes.ISTORE..Opcodes.ASTORE && instructions.operands[it] == slot }

    private fun sameOperand(
        first: Int,
        second: Int,
        copies: CopyStarts,
        pairedSlots: MutableMap<Int, Int>,
        pairedBack: MutableMap<Int, Int>,
    ): Boolean {
        val a = instructions.operands[first]
        val b = instructions.operands[second]
        if (a !is Int || b !is Int) return a == b
        val nameA = instructions.localName(first, a)
        val nameB = instructions.localName(second, b)
        if (nameA != null && nameB != null) return nameA == nameB
        if (nameA != null || nameB != null) return false
        if (!storesTo(a, copies.exception, first) || !storesTo(b, copies.normal, second)) return a == b
        return pairedSlots.getOrPut(a) { b } == b && pairedBack.getOrPut(b) { a } == a
    }

    private fun endsStatement(ordinal: Int): Boolean {
        val opcode = instructions.opcodes[ordinal]
        return opcode in Opcodes.ISTORE..Opcodes.ASTORE ||
            opcode in Opcodes.IFEQ..Opcodes.LOOKUPSWITCH ||
            opcode in Opcodes.IRETURN..Opcodes.RETURN ||
            opcode == Opcodes.ATHROW ||
            opcode == Opcodes.POP ||
            opcode == Opcodes.POP2 ||
            opcode == Opcodes.IFNULL ||
            opcode == Opcodes.IFNONNULL
    }

    private fun nullSide(ordinal: Int): Int {
        val opcodes = instructions.opcodes
        return when (opcodes[ordinal]) {
            Opcodes.IFNULL -> 0
            Opcodes.IFNONNULL -> 1
            Opcodes.IF_ACMPEQ -> if (comparesWithNull(ordinal)) 0 else -1
            Opcodes.IF_ACMPNE -> if (comparesWithNull(ordinal)) 1 else -1
            else -> -1
        }
    }

    private fun comparesWithNull(ordinal: Int): Boolean {
        val opcodes = instructions.opcodes
        if (ordinal in jumpTargets) return false
        if (ordinal >= 1 && opcodes[ordinal - 1] == Opcodes.ACONST_NULL) return true
        return ordinal >= 2 &&
            opcodes[ordinal - 2] == Opcodes.ACONST_NULL &&
            opcodes[ordinal - 1] == Opcodes.ALOAD &&
            (ordinal - 1) !in jumpTargets
    }

    private fun findFinallyHandlers(): List<Int> =
        instructions.tryCatches
            .filter { it.type == null }
            .map { it.handler }
            .distinct()
            .filter(::rethrows)

    private fun rethrows(handler: Int): Boolean {
        val opcodes = instructions.opcodes
        if (opcodes[handler] != Opcodes.ASTORE) return false
        val caught = instructions.operands[handler] as? Int ?: return false
        return (handler + 1 until realCount - 1).any { ordinal ->
            opcodes[ordinal] == Opcodes.ALOAD &&
                instructions.operands[ordinal] == caught &&
                opcodes[ordinal + 1] == Opcodes.ATHROW &&
                dominance.dominates(handler, ordinal) &&
                dominance.dominates(handler, ordinal + 1)
        }
    }

    private fun alwaysThrows(handler: Int): Boolean {
        val visited = HashSet<Int>()
        val pending = ArrayDeque<Int>()
        pending.addLast(handler)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (!visited.add(node)) continue
            if (!dominance.dominates(handler, node)) return false
            if (node < realCount) {
                val opcode = instructions.opcodes[node]
                if (opcode == Opcodes.ATHROW) continue
                if (opcode in Opcodes.IRETURN..Opcodes.RETURN) return false
            }
            graph.normalSuccessors[node].forEach(pending::addLast)
            for (nested in instructions.tryCatches) {
                if (node in nested.start until nested.end && dominance.dominates(handler, nested.handler)) {
                    pending.addLast(nested.handler)
                }
            }
        }
        return true
    }

    private fun caughtHere(ordinal: Int): Boolean = handlingCatches.any { ordinal >= it.start && ordinal < it.end }

    private class Path(
        val throwOnly: Boolean,
        val nullDefault: Boolean,
    )

    private fun walk(
        outcomeNode: Int,
        isNullSide: Boolean,
    ): Path {
        var throwOnly = true
        var nullDefault = isNullSide
        var throws = false
        val visited = HashSet<Int>()
        val pending = ArrayDeque<Int>()
        graph.normalSuccessors[outcomeNode].forEach(pending::addLast)
        while (pending.isNotEmpty() && (throwOnly || nullDefault)) {
            val node = pending.removeLast()
            if (!visited.add(node)) continue
            if (!dominance.dominates(outcomeNode, node)) {
                throwOnly = false
                continue
            }
            if (node < realCount) {
                when (val step = step(node)) {
                    Step.RETURN -> {
                        throwOnly = false
                        continue
                    }

                    Step.THROW -> {
                        throws = true
                        nullDefault = false
                        if (caughtHere(node)) throwOnly = false
                        continue
                    }

                    else -> {
                        if (step != Step.PLAIN) nullDefault = false
                        if (step == Step.OTHER) throwOnly = false
                    }
                }
            }
            graph.normalSuccessors[node].forEach(pending::addLast)
        }
        return Path(throwOnly && throws, nullDefault)
    }

    private enum class Step {
        PLAIN,

        RETURN,

        THROW,

        THROW_PATH,

        OTHER,
    }

    private fun step(ordinal: Int): Step {
        val opcode = instructions.opcodes[ordinal]
        val operand = instructions.operands[ordinal]
        return when {
            opcode in Opcodes.IRETURN..Opcodes.RETURN -> Step.RETURN
            opcode == Opcodes.ATHROW -> Step.THROW
            opcode == Opcodes.INVOKEDYNAMIC -> if (isStringConcat(operand as? CalledMethod)) Step.THROW_PATH else Step.OTHER
            opcode in Opcodes.INVOKEVIRTUAL..Opcodes.INVOKEINTERFACE -> callStep(operand as? CalledMethod)
            opcode == Opcodes.NEW -> newStep(operand as? String)
            isPlain(opcode) -> Step.PLAIN
            opcode in THROW_PATH_FORBIDDEN -> Step.OTHER
            else -> Step.THROW_PATH
        }
    }

    private fun callStep(called: CalledMethod?): Step {
        if (called == null) return Step.OTHER
        val owner = called.owner
        val name = called.name
        return when {
            owner.endsWith(INTRINSICS_SUFFIX) && name in THROWING_INTRINSICS -> Step.THROW
            owner.endsWith(INTRINSICS_SUFFIX) && name == "stringPlus" -> Step.THROW_PATH
            owner == STRING_BUILDER && (name == "<init>" || name == "append" || name == "toString") -> Step.THROW_PATH
            owner == STRING && name == "valueOf" -> Step.THROW_PATH
            (owner == OBJECT || owner == STRING) && name == "toString" && called.descriptor == "()Ljava/lang/String;" -> Step.THROW_PATH
            name == "<init>" && isThrowable(owner) -> Step.THROW_PATH
            else -> Step.OTHER
        }
    }

    private fun newStep(type: String?): Step =
        when {
            type == null -> Step.OTHER
            type == STRING_BUILDER || isThrowable(type) -> Step.THROW_PATH
            else -> Step.OTHER
        }

    private fun isStringConcat(called: CalledMethod?): Boolean =
        called != null &&
            called.owner == "java/lang/invoke/StringConcatFactory" &&
            (called.name == "makeConcatWithConstants" || called.name == "makeConcat")

    private fun isPlain(opcode: Int): Boolean =
        opcode == Opcodes.NOP ||
            opcode in Opcodes.ACONST_NULL..Opcodes.LDC ||
            opcode in Opcodes.ILOAD..Opcodes.ALOAD ||
            opcode in Opcodes.ISTORE..Opcodes.ASTORE ||
            opcode in Opcodes.POP..Opcodes.SWAP ||
            opcode in Opcodes.IFEQ..Opcodes.GOTO ||
            opcode == Opcodes.TABLESWITCH ||
            opcode == Opcodes.LOOKUPSWITCH ||
            opcode == Opcodes.IFNULL ||
            opcode == Opcodes.IFNONNULL ||
            opcode == Opcodes.CHECKCAST

    private companion object {
        // shadowJar rewrites any string in the agent's classes that starts with "kotlin", so this holds only a suffix.
        const val INTRINSICS_SUFFIX = "/jvm/internal/Intrinsics"
        const val STRING_BUILDER = "java/lang/StringBuilder"
        const val STRING = "java/lang/String"
        const val OBJECT = "java/lang/Object"

        const val LEAD_UP = 4

        val THROWING_INTRINSICS =
            setOf("throwUninitializedPropertyAccessException", "throwUninitializedProperty", "throwNpe", "throwJavaNpe")

        val THROW_PATH_FORBIDDEN =
            setOf(
                Opcodes.PUTFIELD,
                Opcodes.PUTSTATIC,
                Opcodes.IASTORE,
                Opcodes.LASTORE,
                Opcodes.FASTORE,
                Opcodes.DASTORE,
                Opcodes.AASTORE,
                Opcodes.BASTORE,
                Opcodes.CASTORE,
                Opcodes.SASTORE,
                Opcodes.MONITORENTER,
                Opcodes.MONITOREXIT,
                Opcodes.JSR,
                Opcodes.RET,
            )
    }
}
