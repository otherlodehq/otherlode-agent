package dev.otherlode.instrumentation.branch

import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes

enum class SizeGuardReason(
    /** What the WARNING says about the method. */
    val text: String,
    /** The code length the weave would have crossed. */
    val limit: Int,
) {
    NOT_COMPILED("would not be JIT-compiled", SizeGuard.HUGE_METHOD_LIMIT),

    CLASS_FILE_LIMIT("would not fit a class file", SizeGuard.CODE_LENGTH_LIMIT),

    /** The entry probe alone can take the method past `HugeMethodLimit`. [SizeGuard] names the method and drops no branch probe. */
    ENTRY_PROBE_ALONE("can be carried past the compile limit by its entry probe alone", SizeGuard.HUGE_METHOD_LIMIT),
}

/** A method [SizeGuard] named: why, and the lengths it judged. */
data class SizeGuardVerdict(
    val reason: SizeGuardReason,
    val original: Int,
    val bound: Int,
) {
    /** The WARNING for [className]'s method [name] with [descriptor]. */
    fun message(
        className: String,
        name: String,
        descriptor: String,
    ): String =
        if (reason == SizeGuardReason.ENTRY_PROBE_ALONE) {
            "otherlode: $className#$name$descriptor is $original bytes of code and its entry probe can take it to $bound, " +
                "over the ${reason.limit} byte limit; HotSpot may not compile it, and its branch probes are kept"
        } else {
            "otherlode: $className#$name$descriptor ${reason.text} with branch probes: it is $original bytes of code " +
                "and could grow to $bound bytes, over the ${reason.limit} byte limit; " +
                "its branch probes are left out and its entry probe is kept"
        }
}

/** A method's code length in the class file and the upper bound on its woven length with every kept probe. */
data class CodeSizeBound(
    val original: Int,
    val bound: Int,
)

/** What [SizeGuard.apply] found for one class. */
class SizeGuardResult(
    /**
     * The bound for every method that has at least one tracked site, dropped or kept, unless its entry probe
     * alone takes it past 65535 bytes.
     */
    val bounds: Map<Pair<String, String>, CodeSizeBound>,
    /** The methods whose branch probes were dropped. */
    val guarded: Map<Pair<String, String>, SizeGuardVerdict>,
    /** The methods the entry probe alone carries past the compile limit; their branch probes are kept. */
    val entryPastLimit: Map<Pair<String, String>, SizeGuardVerdict> = emptyMap(),
) {
    companion object {
        val NONE = SizeGuardResult(emptyMap(), emptyMap())
    }
}

internal class SiteCost(
    val isSwitch: Boolean,
    val outcomes: Int,
    val dropped: Boolean,
)

/**
 * Keeps weaving from pushing a method past HotSpot's `HugeMethodLimit` of 8000 bytes, above which a method
 * is never JIT-compiled, or past the class file's 65535 bytes of code per method, above which the class
 * cannot be written and is skipped whole.
 *
 * When the bound crosses a limit, [apply] drops every still-kept branch site of that method as
 * [BranchDropReason.SIZE_GUARD], unless the entry probe alone takes the method past that limit or past
 * 65535. The method keeps its entry probe.
 *
 * The 8000 decision reads the class file alone. The 65535 decision reads the larger of the class file
 * and the received bytes.
 */
object SizeGuard {
    /** HotSpot's `HugeMethodLimit`. HotSpot never JIT-compiles a method over it. */
    const val HUGE_METHOD_LIMIT = 8000

    /** The most code a method can hold. */
    const val CODE_LENGTH_LIMIT = 65535

    /** The code length above which a jump offset may no longer fit in 16 bits. */
    const val WIDENING_THRESHOLD = 32767

    const val ARRAY_LOAD = 3
    const val INDEX_PUSH = 3
    const val PROBE_INCREMENT = ARRAY_LOAD + INDEX_PUSH + 5

    const val ENTRY_PROBE = PROBE_INCREMENT

    private const val CONDITIONAL_JUMP = 3

    const val GOTO = 3

    /** What a conditional gains when both outcomes are probed. */
    const val TWO_WAY_SITE = 2 * (PROBE_INCREMENT + GOTO) + GOTO - CONDITIONAL_JUMP
    const val SWITCH_CASE = PROBE_INCREMENT + GOTO
    const val SWITCH_PADDING = 3

    /** The most ASM adds when it widens one jump. */
    const val WIDENING_PER_JUMP = 5

    private const val TWO_WAY_INSERTED_JUMPS = 2

    private const val TWO_WAY = 0

    /**
     * The upper bound on the woven length of a method of [length] bytes, with a probe on every site of
     * [sites] that is not already dropped. [jumpCount] gives the method's own jump count, and this asks
     * it only above [WIDENING_THRESHOLD].
     */
    fun bound(
        length: Int,
        sites: List<BranchSite>,
        jumpCount: () -> Int,
    ): Int = boundOf(length, sites.map { SiteCost(it.isSwitch, it.outcomeCount, it.dropReason != null) }, jumpCount)

    internal fun boundOf(
        length: Int,
        sites: List<SiteCost>,
        jumpCount: () -> Int,
    ): Int {
        var added = ENTRY_PROBE
        var insertedJumps = 0
        for (site in sites) {
            if (site.isSwitch) added += SWITCH_PADDING
            if (site.dropped) continue
            if (site.isSwitch) {
                added += site.outcomes * SWITCH_CASE
                insertedJumps += site.outcomes
            } else {
                added += TWO_WAY_SITE
                insertedJumps += TWO_WAY_INSERTED_JUMPS
            }
        }
        val unwidened = length + added
        if (unwidened <= WIDENING_THRESHOLD) return unwidened
        return unwidened + WIDENING_PER_JUMP * (jumpCount() + insertedJumps)
    }

    private fun entryOnly(
        length: Int,
        sites: List<SiteCost>,
    ): Int = length + ENTRY_PROBE + SWITCH_PADDING * sites.count { it.isSwitch }

    internal fun apply(
        sites: MutableList<BranchSite>,
        classBytes: ByteArray,
        receivedBytes: ByteArray?,
        isProbed: (name: String, descriptor: String) -> Boolean,
        droppedOrdinalsByMethod: MutableMap<Pair<String, String>, MutableSet<Int>>,
        classReader: ClassReader? = null,
        jumpOpcodes: (Pair<String, String>) -> IntArray?,
    ): SizeGuardResult {
        val lengths = readLengths(classBytes, classReader) ?: return SizeGuardResult.NONE
        val received = receivedBytes?.takeIf { !it.contentEquals(classBytes) }?.let { readLengths(it) }
        val indicesByMethod = LinkedHashMap<Pair<String, String>, MutableList<Int>>()
        sites.forEachIndexed { index, site ->
            indicesByMethod.getOrPut(site.methodName to site.methodDescriptor) { mutableListOf() } += index
        }
        val receivedJumps by lazy { receivedBytes?.let(::jumpCounts).orEmpty() }
        val bounds = LinkedHashMap<Pair<String, String>, CodeSizeBound>()
        val guarded = LinkedHashMap<Pair<String, String>, SizeGuardVerdict>()
        val entryPast = LinkedHashMap<Pair<String, String>, SizeGuardVerdict>()
        for ((key, original) in lengths) {
            val indices = indicesByMethod[key].orEmpty()
            if (indices.isEmpty() && !isProbed(key.first, key.second)) continue
            val costs = indices.map { SiteCost(sites[it].isSwitch, sites[it].outcomeCount, sites[it].dropReason != null) }
            val receivedLength = received?.get(key) ?: original
            val longest = maxOf(original, receivedLength)
            if (entryOnly(longest, costs) > CODE_LENGTH_LIMIT) continue
            val bound = boundOf(original, costs) { jumpOpcodes(key)?.count { isJump(it) } ?: 0 }
            val longestBound =
                if (longest == original) {
                    bound
                } else {
                    boundOf(longest, costs) { receivedJumps[key] ?: ((longest - original) / GOTO) }
                }
            if (indices.isNotEmpty()) bounds[key] = CodeSizeBound(original, maxOf(bound, longestBound))
            val entryOnlyLength = entryOnly(original, costs)
            val hasKept = costs.any { !it.dropped }
            val verdict =
                when {
                    hasKept && longestBound > CODE_LENGTH_LIMIT -> {
                        SizeGuardVerdict(SizeGuardReason.CLASS_FILE_LIMIT, longest, longestBound)
                    }

                    hasKept && original <= HUGE_METHOD_LIMIT && bound > HUGE_METHOD_LIMIT && entryOnlyLength <= HUGE_METHOD_LIMIT -> {
                        SizeGuardVerdict(SizeGuardReason.NOT_COMPILED, original, bound)
                    }

                    original <= HUGE_METHOD_LIMIT && entryOnlyLength > HUGE_METHOD_LIMIT && (!hasKept || bound > HUGE_METHOD_LIMIT) -> {
                        entryPast[key] = SizeGuardVerdict(SizeGuardReason.ENTRY_PROBE_ALONE, original, entryOnlyLength)
                        null
                    }

                    else -> {
                        null
                    }
                } ?: continue
            indices.forEachIndexed { ordinal, index ->
                if (sites[index].dropReason != null) return@forEachIndexed
                sites[index] = sites[index].copy(dropReason = BranchDropReason.SIZE_GUARD, condition = emptyList())
                droppedOrdinalsByMethod.getOrPut(key) { mutableSetOf() } += ordinal
            }
            guarded[key] = verdict
        }
        return SizeGuardResult(bounds, guarded, entryPast)
    }

    internal fun reweaveGuarded(
        receivedBytes: ByteArray,
        methods: Collection<Pair<String, String>>,
        sequences: (Pair<String, String>) -> IntArray?,
        dropped: (Pair<String, String>) -> Set<Int>,
    ): Set<Pair<String, String>> {
        val lengths = readLengths(receivedBytes) ?: return emptySet()
        val jumps by lazy { jumpCounts(receivedBytes) }
        val guarded = LinkedHashSet<Pair<String, String>>()
        for (key in methods) {
            val length = lengths[key] ?: continue
            val already = dropped(key)
            val costs =
                SitePairing.outcomeBounds(sequences(key) ?: continue).mapIndexed { ordinal, outcomes ->
                    SiteCost(outcomes != TWO_WAY, outcomes, ordinal in already)
                }
            if (costs.all { it.dropped } || entryOnly(length, costs) > CODE_LENGTH_LIMIT) continue
            if (boundOf(length, costs) { jumps[key] ?: 0 } > CODE_LENGTH_LIMIT) guarded += key
        }
        return guarded
    }

    private fun readLengths(
        bytes: ByteArray,
        reader: ClassReader? = null,
    ): Map<Pair<String, String>, Int>? =
        try {
            codeLengths(reader ?: ClassReader(bytes))
        } catch (_: RuntimeException) {
            null
        }

    private fun jumpCounts(bytes: ByteArray): Map<Pair<String, String>, Int> {
        val counts = HashMap<Pair<String, String>, Int>()
        try {
            ClassReader(bytes).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor {
                        val key = name to descriptor
                        return object : MethodVisitor(Opcodes.ASM9) {
                            override fun visitJumpInsn(
                                opcode: Int,
                                label: Label,
                            ) {
                                counts.merge(key, 1, Int::plus)
                            }
                        }
                    }
                },
                ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )
        } catch (_: RuntimeException) {
            return emptyMap()
        }
        return counts
    }

    private fun isJump(opcode: Int): Boolean =
        opcode in Opcodes.IFEQ..Opcodes.JSR || opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL

    internal fun codeLengths(classBytes: ByteArray): Map<Pair<String, String>, Int> = codeLengths(ClassReader(classBytes))

    internal fun codeLengths(reader: ClassReader): Map<Pair<String, String>, Int> {
        val buffer = CharArray(reader.maxStringLength)
        var offset = reader.header + CLASS_HEADER_REST
        offset += 2 + 2 * reader.readUnsignedShort(offset)
        repeat(reader.readUnsignedShort(offset).also { offset += 2 }) {
            offset = skipAttributes(reader, offset + MEMBER_HEADER)
        }
        val lengths = HashMap<Pair<String, String>, Int>()
        repeat(reader.readUnsignedShort(offset).also { offset += 2 }) {
            val name = reader.readUTF8(offset + 2, buffer)
            val descriptor = reader.readUTF8(offset + 4, buffer)
            var at = offset + MEMBER_HEADER
            repeat(reader.readUnsignedShort(at).also { at += 2 }) {
                val length = reader.readInt(at + 2)
                if (reader.readUTF8(at, buffer) == "Code") lengths[name to descriptor] = reader.readInt(at + 6 + 4)
                at += ATTRIBUTE_HEADER + length
            }
            offset = at
        }
        return lengths
    }

    private fun skipAttributes(
        reader: ClassReader,
        start: Int,
    ): Int {
        var at = start
        repeat(reader.readUnsignedShort(at).also { at += 2 }) { at += ATTRIBUTE_HEADER + reader.readInt(at + 2) }
        return at
    }

    private const val CLASS_HEADER_REST = 6

    private const val MEMBER_HEADER = 6

    private const val ATTRIBUTE_HEADER = 6
}
