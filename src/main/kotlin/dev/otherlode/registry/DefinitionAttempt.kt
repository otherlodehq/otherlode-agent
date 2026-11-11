package dev.otherlode.registry

import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

internal class DefinitionAttempt(
    thread: Thread,
    frames: List<String>,
) {
    private val threadRef = WeakReference(thread)

    private val frames: Array<String> = frames.toTypedArray()

    internal fun heldFrames(): List<String> = frames.asList()

    val frameClass: String get() = frames[0].substringBefore('#')

    val frameMethod: String get() = frames[0].substringAfter('#')

    fun hasEnded(): Boolean {
        val thread = threadRef.get() ?: return true
        if (!thread.isAlive) return true
        return !heldBy(thread.stackTrace)
    }

    // Thread.getStackTrace of another thread drops bottom frames past MaxJavaStackTraceDepth and can show
    // hidden frames StackWalker leaves out. A doubtful read (that long, empty or with another bottom frame)
    // counts as held, so it keeps the class pending. The held frames match as a subsequence.
    internal fun heldBy(stack: Array<StackTraceElement>): Boolean {
        if (stack.size >= readLimit) return true
        val bottom = stack.lastOrNull() ?: return true
        if (keyOf(bottom) != frames.last()) return true
        var held = frames.size - 1
        var index = stack.size - 1
        while (held >= 0 && index >= 0) {
            if (keyOf(stack[index]) == frames[held]) held--
            index--
        }
        return held < 0
    }

    companion object {
        private const val TRANSFORMER_ENTRY_CLASS = "sun.instrument.InstrumentationImpl"
        private const val TRANSFORMER_ENTRY_METHOD = "transform"

        private val walker = StackWalker.getInstance(StackWalker.Option.SHOW_REFLECT_FRAMES)

        private fun keyOf(frame: StackTraceElement): String = frame.className + "#" + frame.methodName

        private const val DEFAULT_READ_LIMIT = 1024

        private const val LIMIT_PROBE_DEPTH = 256

        @Volatile
        private var readLimit = DEFAULT_READ_LIMIT

        @Volatile
        internal var stackDepthReader: () -> Int = { Thread.currentThread().stackTrace.size }

        private val deepestFullRead = AtomicInteger()

        internal fun currentReadLimit(): Int = readLimit

        internal fun resetLearnedDepths() {
            readLimit = DEFAULT_READ_LIMIT
            deepestFullRead.set(0)
        }

        private fun learnReadLimit(total: Int) {
            if (total < LIMIT_PROBE_DEPTH || total <= deepestFullRead.get()) return
            val read = stackDepthReader()
            if (read < total) {
                if (read < readLimit) readLimit = read
            } else {
                deepestFullRead.accumulateAndGet(total, ::maxOf)
            }
        }

        fun capture(): DefinitionAttempt? = capture(TRANSFORMER_ENTRY_CLASS, TRANSFORMER_ENTRY_METHOD)

        internal fun capture(
            entryClass: String,
            entryMethod: String,
        ): DefinitionAttempt? =
            walker.walk { stream ->
                val held = ArrayList<String>()
                var afterEntry = false
                var total = 0
                stream.forEach { frame ->
                    total++
                    if (afterEntry) {
                        held += (frame.className + "#" + frame.methodName).intern()
                    } else if (frame.className == entryClass && frame.methodName == entryMethod) {
                        afterEntry = true
                    }
                }
                if (held.isEmpty()) {
                    null
                } else {
                    learnReadLimit(total)
                    DefinitionAttempt(Thread.currentThread(), held)
                }
            }
    }
}
