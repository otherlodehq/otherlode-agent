package dev.otherlode.instrumentation.branch

import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes

/** Why [SizeGuard] named a method. */
enum class SizeGuardReason(
    /** What the WARNING says about the method. */
    val text: String,
    /** The code length the weave would have crossed. */
    val limit: Int,
) {
    /** HotSpot never compiles a method over `HugeMethodLimit` bytes, so probes would leave it interpreted for good. */
    NOT_COMPILED("would not be JIT-compiled", SizeGuard.HUGE_METHOD_LIMIT),

    /** A method's code is capped at 65535 bytes; weaving past it fails the whole class. */
    CLASS_FILE_LIMIT("would not fit a class file", SizeGuard.CODE_LENGTH_LIMIT),

    /**
     * The entry probe alone can take the method past `HugeMethodLimit`, judged at the entry probe's
     * largest encoding. Dropping branch probes cannot bring it back under, so nothing is dropped and
     * the method is only named.
     */
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
    /** The bound for every method that has at least one tracked site, dropped or kept. */
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

/** One tracked site as [SizeGuard] weighs it: a switch has [outcomes] blocks, a two-way jump ignores it. */
internal class SiteCost(
    val isSwitch: Boolean,
    val outcomes: Int,
    val dropped: Boolean,
)

/**
 * Keeps weaving from pushing a method past two limits: HotSpot's `HugeMethodLimit` of 8000 bytes,
 * above which a method is never JIT-compiled, and the class file's 65535 bytes of code per method,
 * above which the class cannot be written and is skipped whole.
 *
 * [apply] bounds each method's woven code length from its length in the class file and its tracked
 * sites, and when the bound crosses a limit it drops every one of that method's still-kept branch
 * sites as [BranchDropReason.SIZE_GUARD]. A dropped site is emitted unchanged and keeps its
 * identity, as for any other drop, and the method keeps its entry probe, so a missing branch
 * finding is a missed finding and never a false one.
 *
 * The 8000 decision reads the class file alone, so an instance reports the same manifest whatever
 * transformer ran ahead of this agent. The 65535 decision reads the larger of the class file and
 * the received bytes, since the rewrite walks the received bytes and an earlier transformer may
 * have grown them.
 *
 * The bound is the original length plus an upper bound on each construct the weave inserts. Each
 * constant below is read from what [BranchProbeMethodVisitor] and the entry advice emit:
 *
 * - [ARRAY_LOAD] 3: `ldc_w` of the dynamic constant, or `invokestatic` of the accessor. Never more.
 * - [INDEX_PUSH] 3: `iconst`, `bipush` and `sipush` take 1 to 3 bytes; an index past 32767 is an
 *   `ldc` (2) or `ldc_w` (3).
 * - [PROBE_INCREMENT] 11: the load, the index, then `dup2 laload lconst_1 ladd lastore`, 5 bytes.
 * - [ENTRY_PROBE] 15: the same increment, plus [ADVICE_TAIL], the `goto` that replaces the advice's
 *   `return` and the `nop` ByteBuddy puts before the original code.
 * - [TWO_WAY_SITE] 28: a conditional becomes `jump taken; probe; goto continue; taken: probe; goto
 *   target`, 31 bytes in place of the jump's 3. A site with one probed outcome takes less.
 * - [SWITCH_CASE] 14: each case, and the default when probed, gets a block of a probe and a `goto`.
 *   The switch instruction itself keeps its size.
 * - [SWITCH_PADDING] 3: a switch pads to a four-byte boundary, so code inserted before it moves the
 *   padding by up to three bytes either way. Every switch counts, a dropped one included, since it is
 *   emitted unchanged but still moves.
 *
 * The bound counts the probes the site's outcomes ask for, and the original count for a switch
 * (one block per case entry plus the default), so it is never below what the rewriter emits.
 *
 * ASM widens a jump whose offset does not fit 16 bits: `goto` to `goto_w` (2 more bytes) and a
 * conditional to an inverted jump over a `goto_w` (5 more). An offset cannot exceed the code length,
 * so while the bound without widening is at most [WIDENING_THRESHOLD] (32767) nothing widens, and
 * above it [WIDENING_PER_JUMP] bytes are added for every jump the woven method could hold: the
 * method's own jumps plus the two a two-way site inserts and the one per switch block. That counts
 * every jump as widened, which over-states what ASM does and never under-states it.
 */
object SizeGuard {
    /** HotSpot's `HugeMethodLimit`: a method over it is never compiled. */
    const val HUGE_METHOD_LIMIT = 8000

    /** The most code a method can hold. */
    const val CODE_LENGTH_LIMIT = 65535

    /** The code length above which a jump offset may no longer fit in 16 bits. */
    const val WIDENING_THRESHOLD = 32767

    const val ARRAY_LOAD = 3
    const val INDEX_PUSH = 3
    const val PROBE_INCREMENT = ARRAY_LOAD + INDEX_PUSH + 5

    /** The `goto` that replaces the advice's `return`, 3 bytes, and the `nop` ByteBuddy puts before the original code. */
    const val ADVICE_TAIL = 4
    const val ENTRY_PROBE = PROBE_INCREMENT + ADVICE_TAIL

    /** A conditional jump with a 16-bit offset. */
    private const val CONDITIONAL_JUMP = 3

    /** A `goto` with a 16-bit offset. */
    const val GOTO = 3

    /** What a conditional gains when both outcomes are probed: its 3-byte jump becomes 31 bytes, so 28 more. */
    const val TWO_WAY_SITE = 2 * (PROBE_INCREMENT + GOTO) + GOTO - CONDITIONAL_JUMP
    const val SWITCH_CASE = PROBE_INCREMENT + GOTO
    const val SWITCH_PADDING = 3

    /** The most ASM adds when it widens one jump: a conditional becomes an inverted jump over a `goto_w`. */
    const val WIDENING_PER_JUMP = 5

    private const val TWO_WAY_INSERTED_JUMPS = 2

    /** What [SitePairing.outcomeBounds] gives for a two-way jump, which has no switch outcomes. */
    private const val TWO_WAY = 0

    /**
     * The upper bound on a method of [length] bytes' woven length with every site of [sites] that is
     * not already dropped probed. [jumpCount] gives the method's own jump count and is asked only
     * above [WIDENING_THRESHOLD].
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

    /** [length] with the entry probe and every switch's padding, which is what a method with no branch probe weighs. */
    private fun entryOnly(
        length: Int,
        sites: List<SiteCost>,
    ): Int = length + ENTRY_PROBE + SWITCH_PADDING * sites.count { it.isSwitch }

    /**
     * Judges each method [isProbed] accepts against [classBytes]'s code lengths, and drops the sites
     * of one whose bound crosses a limit: [sites] is updated in place and the method's ordinals join
     * [droppedOrdinalsByMethod]. [receivedBytes] are the bytes the rewrite will walk when they differ
     * from [classBytes]; the 65535 limit is tested against the larger length, and above 32767 bytes
     * the received method's own jumps are counted from them. [jumpOpcodes] gives a method's recorded
     * class-file opcodes, or null when none were recorded.
     *
     * A method with at most 8000 bytes of code that the entry probe alone pushes past 8000 is named in
     * [SizeGuardResult.entryPastLimit] and loses nothing, whether or not it has sites, since the
     * guard walks every probed method's length and not only those with sites. A class whose method
     * lengths cannot be read is left alone. [classReader] reads [classBytes] when the caller has one.
     */
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
            // The entry probe alone overflows: nothing the guard drops saves the class, which is skipped and reported.
            if (entryOnly(longest, costs) > CODE_LENGTH_LIMIT) continue
            val bound = boundOf(original, costs) { jumpOpcodes(key)?.count { isJump(it) } ?: 0 }
            // Bytes an earlier transformer added may hold jumps of their own, so each is counted from the received method.
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

    /**
     * The methods of a stored plan whose branch probes must go when the plan is woven again against
     * [receivedBytes]: every method in [methods] that still has a kept site and whose bound over the
     * received length crosses 65535. The caller leaves such a method out of the branch rewrite
     * altogether, which keeps the plan's slots as they are and allocates none of them, so its branch
     * counts stay where they were. [sequences] are the plan's encoded tracked instructions and
     * [dropped] its dropped ordinals. The bound counts a switch's outcomes as its entries not bound
     * for the default, plus the default, which is never below the exact count. A method whose entry
     * probe alone overflows is left, as at the first weave.
     */
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

    /** Every method's count of `goto`, conditional and `jsr` instructions, which are the jumps ASM may widen. */
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

    /** The `Code` attribute's code length of each method, keyed by name and descriptor, read from the method table alone. */
    internal fun codeLengths(classBytes: ByteArray): Map<Pair<String, String>, Int> = codeLengths(ClassReader(classBytes))

    /** [codeLengths] over a reader the caller already holds. */
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

    /** access_flags, this_class and super_class. */
    private const val CLASS_HEADER_REST = 6

    /** A field or method's access_flags, name_index and descriptor_index. */
    private const val MEMBER_HEADER = 6

    /** An attribute's name_index and attribute_length. */
    private const val ATTRIBUTE_HEADER = 6
}
