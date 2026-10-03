package dev.otherlode.instrumentation.branch

import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes

/**
 * How each method's tracked instructions in the class file line up with the same method's in the
 * received bytes, when an earlier transformer has rewritten the class.
 *
 * The analysis reads the class file and the rewrite walks the received bytes, so the two numberings
 * have to agree before a probe planted in the received bytes can stand for a site the class file
 * describes. Per method, the Nth tracked instruction in the class file (a [ConditionalJump], a
 * `TABLESWITCH` or a `LOOKUPSWITCH`, in encounter order, the same rule [BranchProbeMethodVisitor]
 * numbers by) pairs with the Nth in the received bytes:
 *
 * - the same conditional opcode keeps the outcome order;
 * - the inverse conditional opcode (`IFEQ` and `IFNE`, `IF_ACMPEQ` and `IF_ACMPNE`, `IFNULL` and
 *   `IFNONNULL`, and so on) swaps the two outcomes, so the received jump's taken edge counts what
 *   the class file's fall-through did, and the other way round. This is the shape a coverage agent
 *   leaves when it inverts a jump to plant its own probe;
 * - a switch pairs with a switch of the same kind, the same keys (or the same bounds, for a
 *   `TABLESWITCH`), and the same entries sent to the default, with the outcome order kept;
 * - anything else, a different number of tracked instructions, or a method the received bytes do
 *   not declare, leaves the method in [unpairedMethods].
 *
 * A method in [unpairedMethods] gets no branch probes at all. Every other method's ordinals carry
 * over one to one, so the class-file ordinals the analysis hands the rewriter (dropped sites,
 * throwing defaults, unprobed outcomes) name the same instructions in the received bytes, and
 * [swappedOrdinalsOf] names the conditionals whose two outcome slots trade places.
 */
class SitePairing private constructor(
    /** Methods, by name and descriptor, whose sites did not pair, in class-file order. */
    val unpairedMethods: Set<Pair<String, String>>,
    private val swappedOrdinalsByMethod: Map<Pair<String, String>, Set<Int>>,
) {
    /** Whether [name]/[descriptor]'s sites paired, or it was never asked about. */
    fun isPaired(
        name: String,
        descriptor: String,
    ): Boolean = (name to descriptor) !in unpairedMethods

    /**
     * The per-method ordinals of [name]/[descriptor]'s conditionals that arrive inverted in the
     * received bytes. Empty for an unpaired method and for one with nothing inverted.
     */
    fun swappedOrdinalsOf(
        name: String,
        descriptor: String,
    ): Set<Int> = swappedOrdinalsByMethod[name to descriptor] ?: emptySet()

    companion object {
        /** The pairing of a class file with itself: every method paired, nothing swapped. */
        val IDENTICAL = SitePairing(emptySet(), emptyMap())

        /**
         * Pairs [methods] (name and descriptor, as the class file declares them) between
         * [classFile] and [receivedBytes]. A method neither declares is not checked at all.
         */
        fun of(
            classFile: ByteArray,
            receivedBytes: ByteArray,
            methods: Collection<Pair<String, String>>,
        ): SitePairing {
            val wanted = methods.toSet()
            val fromClassFile = trackedInstructions(classFile, wanted)
            val received = trackedInstructions(receivedBytes, wanted)
            val unpaired = LinkedHashSet<Pair<String, String>>()
            val swapped = mutableMapOf<Pair<String, String>, Set<Int>>()
            for ((method, expected) in fromClassFile) {
                val actual = received[method]
                val swaps = if (actual == null) null else pairOne(expected, actual)
                when {
                    swaps == null -> unpaired += method
                    swaps.isNotEmpty() -> swapped[method] = swaps
                }
            }
            return SitePairing(unpaired, swapped)
        }

        /** The ordinals [actual] inverts, or null when the two lists do not pair. */
        private fun pairOne(
            expected: List<TrackedInstruction>,
            actual: List<TrackedInstruction>,
        ): Set<Int>? {
            if (expected.size != actual.size) return null
            val swaps = mutableSetOf<Int>()
            for (ordinal in expected.indices) {
                val fromClassFile = expected[ordinal]
                val received = actual[ordinal]
                when {
                    fromClassFile == received -> {}

                    fromClassFile is TrackedInstruction.Jump &&
                        received is TrackedInstruction.Jump &&
                        inverse(fromClassFile.opcode) == received.opcode -> {
                        swaps += ordinal
                    }

                    else -> {
                        return null
                    }
                }
            }
            return swaps
        }

        /** The conditional opcode that tests the opposite of [opcode], or -1 for anything else. */
        internal fun inverse(opcode: Int): Int =
            when (opcode) {
                Opcodes.IFEQ -> Opcodes.IFNE
                Opcodes.IFNE -> Opcodes.IFEQ
                Opcodes.IFLT -> Opcodes.IFGE
                Opcodes.IFGE -> Opcodes.IFLT
                Opcodes.IFGT -> Opcodes.IFLE
                Opcodes.IFLE -> Opcodes.IFGT
                Opcodes.IF_ICMPEQ -> Opcodes.IF_ICMPNE
                Opcodes.IF_ICMPNE -> Opcodes.IF_ICMPEQ
                Opcodes.IF_ICMPLT -> Opcodes.IF_ICMPGE
                Opcodes.IF_ICMPGE -> Opcodes.IF_ICMPLT
                Opcodes.IF_ICMPGT -> Opcodes.IF_ICMPLE
                Opcodes.IF_ICMPLE -> Opcodes.IF_ICMPGT
                Opcodes.IF_ACMPEQ -> Opcodes.IF_ACMPNE
                Opcodes.IF_ACMPNE -> Opcodes.IF_ACMPEQ
                Opcodes.IFNULL -> Opcodes.IFNONNULL
                Opcodes.IFNONNULL -> Opcodes.IFNULL
                else -> -1
            }

        /** Each of [wanted]'s tracked instructions in [bytes], per method, in encounter order. */
        private fun trackedInstructions(
            bytes: ByteArray,
            wanted: Set<Pair<String, String>>,
        ): Map<Pair<String, String>, List<TrackedInstruction>> {
            val result = LinkedHashMap<Pair<String, String>, List<TrackedInstruction>>()
            val visitor =
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor? {
                        if ((name to descriptor) !in wanted) return null
                        val tracked = mutableListOf<TrackedInstruction>()
                        result[name to descriptor] = tracked
                        return TrackedInstructionCollector(tracked)
                    }
                }
            ClassReader(bytes).accept(visitor, ClassReader.SKIP_FRAMES or ClassReader.SKIP_DEBUG)
            return result
        }
    }

    /** One instruction [BranchProbeMethodVisitor] would number, reduced to what pairing compares. */
    private sealed interface TrackedInstruction {
        data class Jump(
            val opcode: Int,
        ) : TrackedInstruction

        /** [toDefault] marks each entry of the jump table that goes to the default label. */
        data class TableSwitch(
            val min: Int,
            val max: Int,
            val toDefault: List<Boolean>,
        ) : TrackedInstruction

        /** [toDefault] marks each key whose target is the default label. */
        data class LookupSwitch(
            val keys: List<Int>,
            val toDefault: List<Boolean>,
        ) : TrackedInstruction
    }

    private class TrackedInstructionCollector(
        private val tracked: MutableList<TrackedInstruction>,
    ) : MethodVisitor(Opcodes.ASM9) {
        override fun visitJumpInsn(
            opcode: Int,
            label: Label,
        ) {
            if (ConditionalJump.isTracked(opcode)) tracked += TrackedInstruction.Jump(opcode)
        }

        override fun visitTableSwitchInsn(
            min: Int,
            max: Int,
            dflt: Label,
            vararg labels: Label,
        ) {
            tracked += TrackedInstruction.TableSwitch(min, max, labels.map { it === dflt })
        }

        override fun visitLookupSwitchInsn(
            dflt: Label,
            keys: IntArray,
            labels: Array<out Label>,
        ) {
            tracked += TrackedInstruction.LookupSwitch(keys.toList(), labels.map { it === dflt })
        }
    }
}
