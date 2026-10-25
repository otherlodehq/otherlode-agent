package dev.otherlode.instrumentation.staticscan

import dev.otherlode.export.ChunkBound

/**
 * Thrown by [StaticBaselineChunker] when one entry of the scan, such as a declared class, breaks
 * [bound] on its own. A class is never split, so no chunk can hold it. The message names the class
 * and the bound and never carries a string constant from the class.
 */
class ClassTooLargeException(
    val className: String,
    val bound: ChunkBound,
) : RuntimeException("$className alone exceeds ${bound.description} for one chunk")
