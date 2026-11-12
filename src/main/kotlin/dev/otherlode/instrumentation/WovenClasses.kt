package dev.otherlode.instrumentation

import dev.otherlode.instrumentation.branch.BranchProbeAsmVisitorWrapper
import java.util.WeakHashMap

internal class DefaultSiteBinding(
    val base: Int,
    val optionalBits: Int,
    val maskParameterIndex: Int,
)

internal class WeavePlan private constructor(
    val classFileHash: Long,
    val hasClassFileHash: Boolean,
    val layoutHash: Long,
    val probeCount: Int,
    val majorVersion: Int,
    private val typeInitializerSlot: Int,
    private val initializerReturnedSlot: Int,
    private val initializerThrewSlot: Int,
    val branchWrapper: Boolean,
    val branchBase: Int,
    val branchSlotCapacity: Int,
    private val signatures: Array<String>,
    private val ints: IntArray,
    private val branchData: Array<IntArray?>,
) {
    @Volatile
    private var logged = 0

    val typeInitializerProbeIndex: Int? get() = typeInitializerSlot.takeIf { it >= 0 }

    val initializerReturnedProbeIndex: Int? get() = initializerReturnedSlot.takeIf { it >= 0 }

    val initializerThrewProbeIndex: Int? get() = initializerThrewSlot.takeIf { it >= 0 }

    fun firstLog(kind: Int): Boolean =
        synchronized(this) {
            if (logged and kind != 0) {
                false
            } else {
                logged = logged or kind
                true
            }
        }

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

    class View(
        val entrySlots: Map<Pair<String, String>, Int>,
        val branchRuns: Map<Pair<String, String>, BranchProbeAsmVisitorWrapper.MethodSlots>,
        val branchEligible: Set<Pair<String, String>>,
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

    class Builder(
        private val classFileHash: Long?,
        private val layoutHash: Long,
        private val probeCount: Int,
        private val majorVersion: Int,
        private val typeInitializerProbeIndex: Int?,
        private val initializerReturnedProbeIndex: Int?,
        private val initializerThrewProbeIndex: Int?,
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
                initializerReturnedProbeIndex ?: -1,
                initializerThrewProbeIndex ?: -1,
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
        const val LOGGED_REFUSAL = 1

        const val LOGGED_FROZEN = 2

        const val LOGGED_FAILURE = 4

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

internal class WovenClasses {
    private val byLoader = WeakHashMap<ClassLoader, MutableMap<String, WeavePlan>>()

    fun record(
        classLoader: ClassLoader?,
        className: String,
        plan: WeavePlan,
    ) {
        if (classLoader == null) return
        synchronized(byLoader) { byLoader.getOrPut(classLoader) { HashMap() }[className] = plan }
    }

    fun find(
        classLoader: ClassLoader?,
        className: String,
    ): WeavePlan? {
        if (classLoader == null) return null
        return synchronized(byLoader) { byLoader[classLoader]?.get(className) }
    }

    companion object {
        private const val OFFSET_BASIS = -3750763034362895579L
        private const val PRIME = 1099511628211L

        // FNV-1a 64. MessageDigest must not run here, since it would initialise a security provider inside a transformer.
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
