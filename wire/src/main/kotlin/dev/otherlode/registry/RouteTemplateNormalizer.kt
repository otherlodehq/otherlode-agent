package dev.otherlode.registry

/** Turns a framework's route spelling and HTTP verb into an identity form. */
object RouteTemplateNormalizer {
    /**
     * Returns the identity route template for [verbatim], with [contextPath], when given, put in front
     * first. The result starts with a slash and does not end with one, except for `/` itself and after an
     * unclosed brace. Runs of slashes outside braces collapse to one. A blank [verbatim] gives the normalized
     * [contextPath], or `/` without one.
     *
     * A path parameter keeps its name and drops any text after a `:` in its braces, so `{id:\d+}.{ext}`
     * becomes `{id}.{ext}`. A whole-segment wildcard or tail, such as `**`, `{name...}` or `<name>`,
     * becomes one `*` segment. `{name:?}` becomes `{name?}`, and `{/name}` becomes a segment of its own,
     * `{name?}`, so `/books{/id}` becomes `/books/{id?}`. A brace form it does not read, such as `{.name}`,
     * is kept, trimmed and without any text after a `:`. An unclosed brace is kept as written, and the
     * slashes after it are kept as they are.
     */
    fun normalize(
        verbatim: String,
        contextPath: String? = null,
    ): String {
        val combined = "${contextPath.orEmpty()}/$verbatim"
        val segments = splitOutsideBraces(combined).filter { it.isNotBlank() }.map(::normalizeSegment)
        return if (segments.isEmpty()) "/" else segments.joinToString("/", prefix = "/")
    }

    /** Returns [verb] trimmed and uppercased, or `*` for a null, blank or `*` verb. */
    fun normalizeVerb(verb: String?): String {
        val trimmed = verb?.trim().orEmpty()
        return if (trimmed.isEmpty() || trimmed == "*") "*" else trimmed.uppercase()
    }

    private fun splitOutsideBraces(path: String): List<String> {
        val segments = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (c in path) {
            when {
                c == '/' && depth == 0 -> {
                    segments += current.toString()
                    current.setLength(0)
                }

                else -> {
                    if (c == '{') depth++
                    if (c == '}' && depth > 0) depth--
                    current.append(c)
                }
            }
        }
        segments += current.toString()
        return segments
    }

    private fun normalizeSegment(segment: String): String {
        if (segment == "*" || segment == "**") return "*"
        if (isWholeSegmentAngleParameter(segment)) return "*"
        val out = StringBuilder()
        var i = 0
        while (i < segment.length) {
            val close = if (segment[i] == '{') matchingBrace(segment, i) else -1
            if (close < 0) {
                out.append(segment[i])
                i++
                continue
            }
            val inner = segment.substring(i + 1, close).trim()
            val wholeSegment = i == 0 && close == segment.length - 1
            if (wholeSegment && (inner.endsWith("...") || inner.startsWith("*"))) return "*"
            if (wholeSegment && inner.startsWith("+") && isSingleVariable(inner.substring(1))) return "*"
            if (inner.startsWith("/") && isSingleVariable(inner.substring(1))) {
                val name = inner.substring(1).substringBefore(":").trim()
                out.append("/{$name?}")
            } else {
                val name = inner.substringBefore(":").trim()
                val optional = inner.contains(":") && inner.substringAfter(":").trim() == "?"
                out.append("{$name${if (optional) "?" else ""}}")
            }
            i = close + 1
        }
        return out.toString().trimStart('/')
    }

    private fun isWholeSegmentAngleParameter(segment: String): Boolean {
        if (!segment.startsWith("<") || !segment.endsWith(">") || segment.length < 2) return false
        val inner = segment.substring(1, segment.length - 1)
        return inner.isNotBlank() && inner.none { it == '<' || it == '>' || it == '{' }
    }

    private fun isSingleVariable(text: String): Boolean {
        val name = text.substringBefore(":").trim()
        return name.isNotEmpty() && name.none { it == ',' || it == '*' } && name[0] !in ".;?&#+/"
    }

    private fun matchingBrace(
        text: String,
        open: Int,
    ): Int {
        var depth = 0
        for (i in open until text.length) {
            if (text[i] == '{') depth++
            if (text[i] == '}' && --depth == 0) return i
        }
        return -1
    }
}
