package dev.otherlode.instrumentation.branch

import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes

/**
 * Pairs each method's tracked instructions (conditional jumps, `TABLESWITCH` and `LOOKUPSWITCH`) in the
 * class file with the same method's in the received bytes, when an earlier transformer has rewritten the class.
 *
 * The Nth tracked instruction in the class file pairs with the Nth in the received bytes when it is the same
 * conditional opcode, the inverse conditional opcode (which swaps the two outcomes), or a switch of the same
 * kind with the same keys or bounds and the same entries sent to the default. Any other difference,
 * a different count, or a method the received bytes do not declare leaves the method in [unpairedMethods].
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
     * This pairing with each of [methods] also left unpaired. [classFileOrder] is the order
     * [unpairedMethods] keeps.
     */
    fun withUnpaired(
        methods: Collection<Pair<String, String>>,
        classFileOrder: List<Pair<String, String>>,
    ): SitePairing {
        if (methods.isEmpty()) return this
        val added = methods.toSet()
        return SitePairing(
            classFileOrder.filterTo(LinkedHashSet()) { it in unpairedMethods || it in added },
            swappedOrdinalsByMethod - added,
        )
    }

    /**
     * The per-method ordinals of [name]/[descriptor]'s conditionals that arrive inverted in the
     * received bytes. Empty for an unpaired method.
     */
    fun swappedOrdinalsOf(
        name: String,
        descriptor: String,
    ): Set<Int> = swappedOrdinalsByMethod[name to descriptor] ?: emptySet()

    companion object {
        /** The pairing of a class file with itself: every method paired, nothing swapped. */
        val IDENTICAL = SitePairing(emptySet(), emptyMap())

        /**
         * Pairs [methods] (name and descriptor) between [classFile] and [receivedBytes]. A method
         * neither declares is not checked.
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

        /**
         * Each of [methods]' tracked instructions in [classFile], encoded for [ofStored]. A method
         * the class file does not declare is left out.
         */
        fun encodedSequences(
            classFile: ByteArray,
            methods: Collection<Pair<String, String>>,
        ): Map<Pair<String, String>, IntArray> = trackedInstructions(classFile, methods.toSet()).mapValues { (_, list) -> encode(list) }

        internal fun outcomeBounds(encoded: IntArray): List<Int> =
            decode(encoded).map {
                when (it) {
                    is TrackedInstruction.Jump -> 0
                    is TrackedInstruction.TableSwitch -> it.toDefault.count { toDefault -> !toDefault } + 1
                    is TrackedInstruction.LookupSwitch -> it.toDefault.count { toDefault -> !toDefault } + 1
                }
            }

        /**
         * Pairs [receivedBytes] with tracked instructions that [encodedSequences] produced. With no
         * [receivedBytes], every method in [stored] is unpaired.
         */
        fun ofStored(
            stored: Map<Pair<String, String>, IntArray>,
            receivedBytes: ByteArray?,
        ): SitePairing {
            if (receivedBytes == null) return SitePairing(LinkedHashSet(stored.keys), emptyMap())
            val received = trackedInstructions(receivedBytes, stored.keys)
            val unpaired = LinkedHashSet<Pair<String, String>>()
            val swapped = mutableMapOf<Pair<String, String>, Set<Int>>()
            for ((method, encoded) in stored) {
                val actual = received[method]
                val swaps = if (actual == null) null else pairOne(decode(encoded), actual)
                when {
                    swaps == null -> unpaired += method
                    swaps.isNotEmpty() -> swapped[method] = swaps
                }
            }
            return SitePairing(unpaired, swapped)
        }

        private fun encode(list: List<TrackedInstruction>): IntArray {
            val out = ArrayList<Int>()
            for (instruction in list) {
                when (instruction) {
                    is TrackedInstruction.Jump -> {
                        out += instruction.opcode
                    }

                    is TrackedInstruction.TableSwitch -> {
                        out += listOf(Opcodes.TABLESWITCH, instruction.min, instruction.max, instruction.toDefault.size)
                        instruction.toDefault.forEach { out += if (it) 1 else 0 }
                    }

                    is TrackedInstruction.LookupSwitch -> {
                        out += listOf(Opcodes.LOOKUPSWITCH, instruction.keys.size)
                        out += instruction.keys
                        instruction.toDefault.forEach { out += if (it) 1 else 0 }
                    }
                }
            }
            return out.toIntArray()
        }

        private fun decode(encoded: IntArray): List<TrackedInstruction> {
            val list = mutableListOf<TrackedInstruction>()
            var at = 0
            while (at < encoded.size) {
                when (val opcode = encoded[at++]) {
                    Opcodes.TABLESWITCH -> {
                        val min = encoded[at++]
                        val max = encoded[at++]
                        val count = encoded[at++]
                        list += TrackedInstruction.TableSwitch(min, max, (0 until count).map { encoded[at + it] == 1 })
                        at += count
                    }

                    Opcodes.LOOKUPSWITCH -> {
                        val count = encoded[at++]
                        val keys = (0 until count).map { encoded[at + it] }
                        at += count
                        list += TrackedInstruction.LookupSwitch(keys, (0 until count).map { encoded[at + it] == 1 })
                        at += count
                    }

                    else -> {
                        list += TrackedInstruction.Jump(opcode)
                    }
                }
            }
            return list
        }

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

        private fun trackedInstructions(
            bytes: ByteArray,
            wanted: Collection<Pair<String, String>>,
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

    private sealed interface TrackedInstruction {
        data class Jump(
            val opcode: Int,
        ) : TrackedInstruction

        data class TableSwitch(
            val min: Int,
            val max: Int,
            val toDefault: List<Boolean>,
        ) : TrackedInstruction

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
