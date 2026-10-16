package dev.otherlode.instrumentation

private const val ELEMENT = "(?:class .*|field .*|method .*|Code attribute for .*|Record component .* of class .*)"

/** What [VerifierMessageParser] made of one `VerifyError` message. */
sealed interface ParsedVerifierMessage {
    /** The message names [className], in dotted form, as a class the verifier rejected. */
    data class Placed(
        val className: String,
    ) : ParsedVerifierMessage

    /** The message is from no class failing to link, or is null. */
    data object Ignored : ParsedVerifierMessage

    /** The message is in no format the agent has read. */
    data object Unplaced : ParsedVerifierMessage
}

/**
 * Reads the class a `VerifyError` message names.
 *
 * HotSpot's split verifier starts a message with a reason line, then `Exception Details:` and a
 * `Location:` whose first token is the class and method. The old inference verifier starts with
 * `(class: NAME, method: ...)` or `(class: NAME) ...`. Those two formats are identical on JDK 17,
 * 21 and 25. JDK 25's class-file API builds `VerifyError`s of its own without any class failing to
 * link, in the formats of `ParserVerifier` and `VerifierImpl`, and they are recognised and
 * ignored. A null message is ignored: HotSpot never links with one, and a failed redefinition
 * builds one. Anything else is unplaced.
 */
object VerifierMessageParser {
    private val SPLIT = Regex("^[^\\n]*\\nException Details:\\n {2}Location:\\n {4}([^.\\n]+)\\.")
    private val OLD = Regex("^\\(class: (.+?)(?:, method: |, field: |\\) )")

    private val CLASS_FILE_API_FORMATS =
        listOf(
            ".* at constant pool index \\d+ in class .*",
            "Illegal field name .* in class .*",
            "Illegal method name .* in class .*",
            "Duplicate interface .* in class .*",
            "Duplicate field name .* with signature .* in class .*",
            "Duplicate method name .* with signature .* in class .*",
            "Method <clinit> is not static in class .*",
            "Interface cannot have a method named <init> in class .*",
            "Multiple .* attributes in $ELEMENT",
            "Code attribute in native or abstract method .*",
            "Arguments can't fit into locals in method .*",
            "Bad constant value type in field .*",
            "Class is both outer and inner class in class .*",
            "Conflicting NestHost and NestMembers attributes in class .*",
            "PermittedSubclasses attribute in final class .*",
            "Wrong .* attribute length in $ELEMENT",
            "Inference verification is not supported",
            "[^\\n]* in [^\\n]+::[^\\n]*\\([^\\n]*\\) @-?\\d+(?:\\s.*)?",
            "[^\\n]* in [^\\n]+::[^\\n]*\\([^\\n]*\\)",
        ).map { Regex(it, RegexOption.DOT_MATCHES_ALL) }

    /** Parses [message], the constructor argument of a `VerifyError`. */
    fun parse(message: String?): ParsedVerifierMessage {
        if (message == null) return ParsedVerifierMessage.Ignored
        SPLIT.find(message)?.let { return placed(it.groupValues[1]) }
        OLD.find(message)?.let { return placed(it.groupValues[1]) }
        if (CLASS_FILE_API_FORMATS.any { it.matches(message) }) return ParsedVerifierMessage.Ignored
        return ParsedVerifierMessage.Unplaced
    }

    private fun placed(internalName: String): ParsedVerifierMessage = ParsedVerifierMessage.Placed(internalName.replace('/', '.'))
}
