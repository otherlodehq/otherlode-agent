package dev.otherlode.config

import dev.otherlode.instrumentation.TypeMatchPolicy
import java.lang.module.ModuleDescriptor
import java.util.jar.JarFile
import java.util.jar.Manifest

internal data class MainClassSuggestion(
    val mainClass: String,
    val prefix: String,
) {
    companion object {
        private val FRAMEWORK_PREFIXES = listOf("org.springframework.boot.loader", "io.ktor.server", "org.apache.catalina")

        private val ARCHIVE_SUFFIXES = listOf(".jar", ".war")

        fun fromCommand(
            command: String?,
            readManifest: (String) -> Manifest? = ::readJarManifest,
            findBootModule: (String) -> ModuleDescriptor? = ::bootModule,
        ): MainClassSuggestion? =
            try {
                mainClassOf(command, readManifest, findBootModule)?.let(::suggestFor)
            } catch (e: Exception) {
                null
            }

        fun readJarManifest(path: String): Manifest? =
            try {
                JarFile(path).use { it.manifest }
            } catch (e: Exception) {
                null
            }

        private fun bootModule(name: String): ModuleDescriptor? =
            ModuleLayer
                .boot()
                .findModule(name)
                .map { it.descriptor }
                .orElse(null)

        private fun mainClassOf(
            command: String?,
            readManifest: (String) -> Manifest?,
            findBootModule: (String) -> ModuleDescriptor?,
        ): String? {
            val token =
                command
                    ?.trim()
                    ?.split(Regex("\\s+"))
                    ?.firstOrNull()
                    ?.takeIf { it.isNotEmpty() } ?: return null
            return when {
                ARCHIVE_SUFFIXES.any { token.endsWith(it, ignoreCase = true) } -> {
                    val attributes = readManifest(token)?.mainAttributes ?: return null
                    attributes.getValue("Start-Class") ?: attributes.getValue("Main-Class")
                }

                token.endsWith(".java") -> {
                    null
                }

                token.contains('/') -> {
                    token.substringAfterLast('/')
                }

                else -> {
                    val module = findBootModule(token)
                    if (module != null) module.mainClass().orElse(null) else token
                }
            }?.trim()
        }

        private fun suggestFor(mainClass: String): MainClassSuggestion? {
            if (!isBinaryClassName(mainClass)) return null
            if (FRAMEWORK_PREFIXES.any { TypeMatchPolicy.isUnderPrefix(mainClass, it) }) return null
            if (!mainClass.contains('.')) return null
            return MainClassSuggestion(mainClass, mainClass.substringBeforeLast('.'))
        }

        private fun isBinaryClassName(name: String): Boolean =
            name.split('.').all { segment ->
                segment.isNotEmpty() && Character.isJavaIdentifierStart(segment[0]) && segment.all(Character::isJavaIdentifierPart)
            }
    }
}

internal object IncludeRulesRefusal {
    fun message(suggestion: MainClassSuggestion?): String {
        val refusal =
            "otherlode: includePackages is not set, so the agent is disabled for this JVM and nothing will be instrumented " +
                "or exported; set includePackages to your application's own package prefixes, ';'-separated"
        if (suggestion == null) return refusal
        return "$refusal; the main class is ${suggestion.mainClass}; includePackages=${suggestion.prefix} covers its " +
            "package, or name a broader prefix that also covers your shared libraries"
    }
}
