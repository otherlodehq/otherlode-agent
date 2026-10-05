package dev.otherlode.instrumentation

import dev.otherlode.instrumentation.branch.BranchProbeAsmVisitorWrapper
import java.util.WeakHashMap

/** A woven `$default` method's per-method constants for the optional-argument advice. */
internal class DefaultSiteBinding(
    val base: Int,
    val optionalBits: Int,
    val maskParameterIndex: Int,
)

/**
 * How the method tier wove one class: everything a later weave of the same class needs to repeat
 * it without reading anything again, so the class keeps its field, its array and its slot
 * numbering.
 *
 * Held for the life of the class's loader, so it is kept compact. Every method the weave touched
 * has an index into [signatures], which holds its name and then its descriptor, interned, and into
 * [ints], which holds [STRIDE] values per method: its entry slot, its branch run's base and count,
 * its omission base, optional bits and mask parameter index, and flags. A method whose branches
 * paired at the first weave also has an entry in [branchData]: the class file's tracked
 * instructions as [dev.otherlode.instrumentation.branch.SitePairing] encodes them, then the
 * dropped, throwing-default and unprobed-outcome ordinals the rewriter needs, each list prefixed
 * by its length.
 *
 * [classFileHash] is [WovenClasses.hashOf] the class file the first weave read, when it read one.
 * [majorVersion] is the class-file version of the bytes the first weave rewrote; it picks the
 * [ProbeArrayForm], and a later weave uses the same one so the class keeps the members it has.
 */
internal class WeavePlan private constructor(
    val classFileHash: Long,
    val hasClassFileHash: Boolean,
    val layoutHash: Long,
    val probeCount: Int,
    val majorVersion: Int,
    private val typeInitializerSlot: Int,
    val branchWrapper: Boolean,
    val branchBase: Int,
    val branchSlotCapacity: Int,
    private val signatures: Array<String>,
    private val ints: IntArray,
    private val branchData: Array<IntArray?>,
) {
    @Volatile
    private var logged = 0

    /** The `<clinit>` probe's slot, or null when the class has no type initializer probe. */
    val typeInitializerProbeIndex: Int? get() = typeInitializerSlot.takeIf { it >= 0 }

    /** Whether this is the first time [kind] is logged for this class; later calls return false. */
    fun firstLog(kind: Int): Boolean =
        synchronized(this) {
            if (logged and kind != 0) {
                false
            } else {
                logged = logged or kind
                true
            }
        }

    /** The plan unpacked into lookups for one weave; built per weave and dropped after it. */
    fun view(): View {
        val entrySlots = HashMap<Pair<String, String>, Int>()
        val runs = HashMap<Pair<String, String>, BranchProbeAsmVisitorWrapper.MethodSlots>()
        val eligible = HashSet<Pair<String, String>>()
        val sequences = LinkedHashMap<Pair<String, String>, IntArray>()
        val dropped = HashMap<Pair<String, String>, Set<Int>>()
        val throwing = HashMap<Pair<String, String>, Set<Int>>()
        val unprobed = HashMap<Pair<String, String>, Map<Int, Int>>()
        val defaults = HashMap<Pair<String, String>, DefaultSiteBinding>()
        for (index in 0 until signatures.size / 2) {
            val key = signatures[2 * index] to signatures[2 * index + 1]
            val at = index * STRIDE
            if (ints[at + ENTRY_SLOT] >= 0) entrySlots[key] = ints[at + ENTRY_SLOT]
            if (ints[at + RUN_COUNT] > 0) runs[key] = BranchProbeAsmVisitorWrapper.MethodSlots(ints[at + RUN_BASE], ints[at + RUN_COUNT])
            if (ints[at + DEFAULT_BASE] >= 0) {
                defaults[key] = DefaultSiteBinding(ints[at + DEFAULT_BASE], ints[at + DEFAULT_BITS], ints[at + MASK_INDEX])
            }
            if (ints[at + FLAGS] and BRANCH_ELIGIBLE == 0) continue
            eligible += key
            val data = branchData[index] ?: continue
            var cursor = 0
            val read = {
                val length = data[cursor++]
                data.copyOfRange(cursor, cursor + length).also { cursor += length }
            }
            sequences[key] = read()
            dropped[key] = read().toSet()
            throwing[key] = read().toSet()
            val pairs = read()
            unprobed[key] = (pairs.indices step 2).associate { pairs[it] to pairs[it + 1] }
        }
        return View(entrySlots, runs, eligible, sequences, dropped, throwing, unprobed, defaults)
    }

    /** One weave's lookups, keyed by method name and descriptor. */
    class View(
        val entrySlots: Map<Pair<String, String>, Int>,
        val branchRuns: Map<Pair<String, String>, BranchProbeAsmVisitorWrapper.MethodSlots>,
        val branchEligible: Set<Pair<String, String>>,
        /** The class file's tracked instructions of each branch-eligible method with bytecode, encoded. */
        val sequences: Map<Pair<String, String>, IntArray>,
        private val dropped: Map<Pair<String, String>, Set<Int>>,
        private val throwingDefaults: Map<Pair<String, String>, Set<Int>>,
        private val unprobed: Map<Pair<String, String>, Map<Int, Int>>,
        val defaultSites: Map<Pair<String, String>, DefaultSiteBinding>,
    ) {
        fun droppedOrdinalsOf(
            name: String,
            descriptor: String,
        ): Set<Int> = dropped[name to descriptor] ?: emptySet()

        fun throwingDefaultOrdinalsOf(
            name: String,
            descriptor: String,
        ): Set<Int> = throwingDefaults[name to descriptor] ?: emptySet()

        fun unprobedOutcomesOf(
            name: String,
            descriptor: String,
        ): Map<Int, Int> = unprobed[name to descriptor] ?: emptyMap()
    }

    /** Collects one first weave's plan, method by method. */
    class Builder(
        private val classFileHash: Long?,
        private val layoutHash: Long,
        private val probeCount: Int,
        private val majorVersion: Int,
        private val typeInitializerProbeIndex: Int?,
        private val branchWrapper: Boolean,
        private val branchBase: Int,
        private val branchSlotCapacity: Int,
    ) {
        private val indexes = LinkedHashMap<Pair<String, String>, Int>()
        private val ints = ArrayList<IntArray>()
        private val branchData = ArrayList<IntArray?>()

        private fun row(
            name: String,
            descriptor: String,
        ): IntArray {
            val index =
                indexes.getOrPut(name to descriptor) {
                    ints += intArrayOf(-1, 0, 0, -1, 0, 0, 0)
                    branchData += null
                    ints.size - 1
                }
            return ints[index]
        }

        fun entrySlot(
            name: String,
            descriptor: String,
            slot: Int,
        ) = apply { row(name, descriptor)[ENTRY_SLOT] = slot }

        /**
         * Marks a method whose branches paired, with the class file's tracked instructions and the
         * rewriter's ordinals. [sequence] is never left out: a later weave pairs the method against
         * it, and an empty one pairs only with bytes that have no tracked instruction either.
         */
        fun branchEligible(
            name: String,
            descriptor: String,
            sequence: IntArray,
            dropped: Set<Int>,
            throwingDefaults: Set<Int>,
            unprobed: Map<Int, Int>,
        ) = apply {
            val row = row(name, descriptor)
            row[FLAGS] = row[FLAGS] or BRANCH_ELIGIBLE
            val pairs = unprobed.entries.flatMap { listOf(it.key, it.value) }
            val parts = listOf(sequence, dropped.toIntArray(), throwingDefaults.toIntArray(), pairs.toIntArray())
            val data = IntArray(parts.sumOf { it.size + 1 })
            var cursor = 0
            for (part in parts) {
                data[cursor++] = part.size
                part.copyInto(data, cursor)
                cursor += part.size
            }
            branchData[indexes.getValue(name to descriptor)] = data
        }

        fun branchRun(
            name: String,
            descriptor: String,
            run: BranchProbeAsmVisitorWrapper.MethodSlots,
        ) = apply {
            val row = row(name, descriptor)
            row[RUN_BASE] = run.base
            row[RUN_COUNT] = run.count
        }

        fun defaultSite(
            name: String,
            descriptor: String,
            binding: DefaultSiteBinding,
        ) = apply {
            val row = row(name, descriptor)
            row[DEFAULT_BASE] = binding.base
            row[DEFAULT_BITS] = binding.optionalBits
            row[MASK_INDEX] = binding.maskParameterIndex
        }

        fun build(): WeavePlan {
            val signatures = arrayOfNulls<String>(indexes.size * 2)
            for ((key, index) in indexes) {
                signatures[2 * index] = key.first.intern()
                signatures[2 * index + 1] = key.second.intern()
            }
            val packed = IntArray(ints.size * STRIDE)
            ints.forEachIndexed { index, row -> row.copyInto(packed, index * STRIDE) }
            @Suppress("UNCHECKED_CAST")
            return WeavePlan(
                classFileHash ?: 0L,
                classFileHash != null,
                layoutHash,
                probeCount,
                majorVersion,
                typeInitializerProbeIndex ?: -1,
                branchWrapper,
                branchBase,
                branchSlotCapacity,
                signatures as Array<String>,
                packed,
                branchData.toTypedArray(),
            )
        }
    }

    companion object {
        /** A refused re-weave, logged once per class. */
        const val LOGGED_REFUSAL = 1

        /** Methods whose branches stopped pairing, logged once per class. */
        const val LOGGED_FROZEN = 2

        /** A re-weave that failed for another reason, logged once per class. */
        const val LOGGED_FAILURE = 4

        /** A re-weave left a method out of the branch rewrite for size, logged once per class. */
        const val LOGGED_SIZE_GUARD = 8

        private const val ENTRY_SLOT = 0
        private const val RUN_BASE = 1
        private const val RUN_COUNT = 2
        private const val DEFAULT_BASE = 3
        private const val DEFAULT_BITS = 4
        private const val MASK_INDEX = 5
        private const val FLAGS = 6
        private const val STRIDE = 7
        private const val BRANCH_ELIGIBLE = 1
    }
}

/**
 * The [WeavePlan] of every class the method tier wove, so a later call for the same loaded class
 * (another agent's retransformation, or a redefinition) can weave it again without renumbering
 * anything and without reading anything but its class file, for one check.
 *
 * A transformer is handed the same arguments for a retransformation and a redefinition, and the
 * bytes it receives depend on what ran ahead of it, which can differ from the first load: another
 * agent's capable transformer may add its own probes, or skip a class it rewrote at load. So
 * nothing here is keyed on the received bytes. The bytes that arrive are paired against the class
 * file's tracked instructions the plan stored. The one thing that rules a weave out is a class
 * file that is readable and differs from the one the plan was made from, since the plan's slots
 * describe that one.
 *
 * Keyed by the defining loader, held weakly, and the class name: the registry's own notion of
 * a class. A collected loader's entries go with it. A class the bootstrap loader defines is
 * never woven, so a null loader is never recorded and never found.
 */
internal class WovenClasses {
    private val byLoader = WeakHashMap<ClassLoader, MutableMap<String, WeavePlan>>()

    /** Remembers how [className], defined by [classLoader], was woven. */
    fun record(
        classLoader: ClassLoader?,
        className: String,
        plan: WeavePlan,
    ) {
        if (classLoader == null) return
        synchronized(byLoader) { byLoader.getOrPut(classLoader) { HashMap() }[className] = plan }
    }

    /** How [className] defined by [classLoader] was woven, or null if this agent never wove it. */
    fun find(
        classLoader: ClassLoader?,
        className: String,
    ): WeavePlan? {
        if (classLoader == null) return null
        return synchronized(byLoader) { byLoader[classLoader]?.get(className) }
    }

    companion object {
        private const val OFFSET_BASIS = -3750763034362895579L // FNV-1a 64-bit offset basis
        private const val PRIME = 1099511628211L // FNV-1a 64-bit prime

        /**
         * FNV-1a 64 over [bytes]. Written here rather than taken from `MessageDigest`, so no
         * security provider is initialised inside a transformer.
         */
        fun hashOf(bytes: ByteArray): Long {
            var hash = OFFSET_BASIS
            for (byte in bytes) {
                hash = hash xor (byte.toLong() and 0xff)
                hash *= PRIME
            }
            return hash
        }
    }
}
