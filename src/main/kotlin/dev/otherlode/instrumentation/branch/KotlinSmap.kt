package dev.otherlode.instrumentation.branch

internal data class SmapEntry(
    val inputStartLine: Int,
    val fileId: Int,
    val outputStartLine: Int,
    val outputLineIncrement: Int,
    val repeatCount: Int,
) {
    fun indexOf(outputLine: Int): Int? {
        if (outputLineIncrement <= 0) return null
        val offset = outputLine - outputStartLine
        if (offset < 0) return null
        val index = offset / outputLineIncrement
        return index.takeIf { it < repeatCount }
    }
}

/**
 * Where an output line's bytecode came from: [inputLine] of [originClassName], dotted, in
 * [sourceFile]. [sourceFile] is a file name such as `Collections.kt`, never a path.
 */
data class SmapOrigin(
    val inputLine: Int,
    val originClassName: String,
    val sourceFile: String,
)

/**
 * The `Kotlin` stratum of a class's `SourceDebugExtension`, parsed by [KotlinSmapParser]. Maps an
 * output line of the class's own bytecode to where kotlinc copied it from, when that line is not
 * the class's own code as written.
 */
class KotlinSmap internal constructor(
    private val entries: List<SmapEntry>,
    private val fileNamesById: Map<Int, String>,
    private val sourceFilesById: Map<Int, String> = emptyMap(),
    private val ownerInternalName: String? = null,
) {
    private val firstFileIsOwn: Boolean =
        ownerInternalName == null ||
            fileNamesById[1].let { it == null || it == sourceFilesById[1] || it == ownerInternalName }

    /** Where [outputLine] came from, or null when it is the class's own code. */
    fun originOf(outputLine: Int): SmapOrigin? {
        for (entry in entries) {
            val index = entry.indexOf(outputLine) ?: continue
            val inputLine = entry.inputStartLine + index
            if (entry.fileId == 1 && inputLine == outputLine && firstFileIsOwn) return null
            val fileName = fileNamesById[entry.fileId] ?: return null
            return SmapOrigin(inputLine, fileName.replace('/', '.'), sourceFilesById[entry.fileId] ?: fileName)
        }
        return null
    }

    companion object {
        val EMPTY = KotlinSmap(emptyList(), emptyMap())
    }
}

/**
 * Parses the `Kotlin` stratum of a class's `SourceDebugExtension`, the JSR-045 SMAP kotlinc
 * writes to record where an inlined copy's bytecode came from. Other strata are skipped.
 */
object KotlinSmapParser {
    private val lineEntryPattern = Regex("""^(\d+)(?:#(\d+))?(?:,(\d+))?:(\d+)(?:,(\d+))?$""")

    /** Parses [debug], the `SourceDebugExtension` text of the class named [ownerInternalName]. */
    fun parse(
        debug: String?,
        ownerInternalName: String? = null,
    ): KotlinSmap {
        if (debug.isNullOrEmpty()) return KotlinSmap.EMPTY
        val lines = debug.split('\n')
        var i = findKotlinStratum(lines) ?: return KotlinSmap.EMPTY
        if (i >= lines.size || lines[i] != "*F") return KotlinSmap.EMPTY
        i++

        val fileNamesById = mutableMapOf<Int, String>()
        val sourceFilesById = mutableMapOf<Int, String>()
        i = parseFileSection(lines, i, fileNamesById, sourceFilesById)

        if (i >= lines.size || lines[i] != "*L") return KotlinSmap(emptyList(), fileNamesById, sourceFilesById, ownerInternalName)
        i++

        val entries = mutableListOf<SmapEntry>()
        var lastFileId = 1
        while (i < lines.size && !lines[i].startsWith("*")) {
            val match = lineEntryPattern.matchEntire(lines[i])
            val inputStartLine = match?.groupValues?.get(1)?.toIntOrNull()
            val outputStartLine = match?.groupValues?.get(4)?.toIntOrNull()
            if (match != null && inputStartLine != null && outputStartLine != null) {
                val fileId = match.groupValues[2].toIntOrNull() ?: lastFileId
                lastFileId = fileId
                entries +=
                    SmapEntry(
                        inputStartLine = inputStartLine,
                        fileId = fileId,
                        outputStartLine = outputStartLine,
                        outputLineIncrement = match.groupValues[5].toIntOrNull() ?: 1,
                        repeatCount = match.groupValues[3].toIntOrNull() ?: 1,
                    )
            }
            i++
        }
        return KotlinSmap(entries, fileNamesById, sourceFilesById, ownerInternalName)
    }

    private fun findKotlinStratum(lines: List<String>): Int? {
        val index = lines.indexOf("*S Kotlin")
        return if (index < 0) null else index + 1
    }

    private fun parseFileSection(
        lines: List<String>,
        start: Int,
        fileNamesById: MutableMap<Int, String>,
        sourceFilesById: MutableMap<Int, String>,
    ): Int {
        var i = start
        while (i < lines.size && lines[i] != "*L") {
            val line = lines[i]
            if (line.startsWith("+ ")) {
                val entry = line.substring(2)
                val id = entry.substringBefore(' ').toIntOrNull()
                if (id != null) sourceFilesById[id] = entry.substringAfter(' ', "")
                i++
                if (id != null && i < lines.size) fileNamesById[id] = lines[i]
                i++
            } else {
                val id = line.substringBefore(' ').toIntOrNull()
                if (id != null) {
                    fileNamesById[id] = line.substringAfter(' ', line)
                    sourceFilesById[id] = line.substringAfter(' ', line)
                }
                i++
            }
        }
        return i
    }
}
