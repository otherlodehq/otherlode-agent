package dev.otherlode.registry

import java.lang.ref.WeakReference

/**
 * The attempt to define one class, as seen from the thread that ran its transformer.
 *
 * The JVM calls the transformer on the thread that defines the class. That thread then stays in
 * the frame that started the definition until the class is defined or the attempt fails. The
 * JVM resolves the class's supertypes inside that frame, so a slow loader keeps it on the stack.
 * [hasEnded] reads the thread's stack and says whether the frame has gone.
 *
 * The attempt holds every frame from the one that started the definition down to the bottom of
 * the stack. The bottom part of a stack does not change while the thread runs deeper calls. So
 * while the attempt runs, a later read of the thread's stack holds these frames, in this order,
 * from its bottom up.
 *
 * A stack read can hold frames the capture did not see. On JDK 17, [Thread.getStackTrace] of
 * another thread shows hidden frames, such as lambda proxies and method handle frames, which
 * [StackWalker] leaves out. [hasEnded] therefore matches the held frames against the read as a
 * subsequence and skips frames it does not know. An extra frame can only make a match easier,
 * so every error here keeps a class pending. None names a class that loaded.
 */
internal class DefinitionAttempt(
    thread: Thread,
    frames: List<String>,
) {
    private val threadRef = WeakReference(thread)

    /** The held frames, each as `class#method`, from the definition frame down to the bottom. */
    private val frames: Array<String> = frames.toTypedArray()

    /** The class of the frame that started the definition. */
    val frameClass: String get() = frames[0].substringBefore('#')

    /** The method of the frame that started the definition. */
    val frameMethod: String get() = frames[0].substringAfter('#')

    /**
     * Whether the attempt is over: its thread was collected or has terminated, or a read of the
     * thread's stack no longer holds the attempt's frames from its bottom up.
     *
     * [Thread.getStackTrace] of another thread can cut a deep stack short at
     * `MaxJavaStackTraceDepth` frames, 1024 by default, and drop its bottom frames. The frame at
     * the cut can carry the same name as the real bottom frame when the bottom method recurses.
     * So a read as long as the cut proves nothing, and the attempt counts as ongoing. So do a read
     * whose bottom frame differs from the one seen at capture and an empty read of a live thread.
     */
    fun hasEnded(): Boolean {
        val thread = threadRef.get() ?: return true
        if (!thread.isAlive) return true
        return !heldBy(thread.stackTrace)
    }

    /**
     * Whether [stack], top first, still holds this attempt. An unreadable stack, empty or cut
     * short, counts as holding it.
     */
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

        /**
         * Shows reflection frames, since [Thread.getStackTrace] shows them. Hidden frames differ
         * between the two on some JDKs, which [heldBy] allows for.
         */
        private val walker = StackWalker.getInstance(StackWalker.Option.SHOW_REFLECT_FRAMES)

        private fun keyOf(frame: StackTraceElement): String = frame.className + "#" + frame.methodName

        /** The default `MaxJavaStackTraceDepth`. */
        private const val DEFAULT_READ_LIMIT = 1024

        /** A capture this deep also reads its own stack, to learn a cut set lower than the default. */
        private const val LIMIT_PROBE_DEPTH = 256

        /**
         * The shortest length a stack read may be cut to. It starts at the default and drops when a
         * capture sees its own read cut shorter. A larger cut leaves it at the default, which only
         * keeps more classes pending.
         */
        @Volatile
        private var readLimit = DEFAULT_READ_LIMIT

        /** Lowers [readLimit] when a read of a stack [total] frames deep came back shorter. */
        private fun learnReadLimit(total: Int) {
            if (total < LIMIT_PROBE_DEPTH) return
            val read = Thread.currentThread().stackTrace.size
            if (read < total && read < readLimit) readLimit = read
        }

        /**
         * Records the attempt that the current thread is in, or null when the current thread is
         * not inside a transformer call.
         *
         * Null covers a direct call to [ProbeRegistry.register] outside the JVM's transformer
         * chain, and a registration made on a thread other than the defining one.
         */
        fun capture(): DefinitionAttempt? = capture(TRANSFORMER_ENTRY_CLASS, TRANSFORMER_ENTRY_METHOD)

        /**
         * Like [capture], with the transformer entry frame named by [entryClass] and
         * [entryMethod]. The frame below it is the definition frame.
         */
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
                        held += frame.className + "#" + frame.methodName
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
