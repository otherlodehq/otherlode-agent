package dev.otherlode.dependencies

import dev.otherlode.export.DependencyIdentity
import dev.otherlode.export.DependencyIdentitySource
import java.util.jar.Attributes

internal data class JarIdentity(
    val identities: List<DependencyIdentity>,
    val identitySource: DependencyIdentitySource,
    val classCount: Int,
)

internal object DependencyIdentityReader {
    private val VERSIONED_STEM = Regex("^(.+?)-((?:\\d+\\.\\d+|\\d+-SNAPSHOT)(?:[.-][A-Za-z0-9_+]+)*)$")
    private val ARCHIVE_EXTENSION = Regex("\\.(jar|zip)$", RegexOption.IGNORE_CASE)

    fun identify(
        contents: JarContents,
        fileName: String,
    ): JarIdentity {
        val classCount = contents.classNames.size
        val fromPoms = fromPomProperties(contents)
        if (fromPoms.isNotEmpty()) return JarIdentity(fromPoms, DependencyIdentitySource.POM_PROPERTIES, classCount)
        val fromFilename = fromFilename(fileName)
        val identity = if (fromFilename.version != null) fromFilename else fromFilename.copy(version = manifestVersion(contents))
        return JarIdentity(listOf(identity), DependencyIdentitySource.FILENAME, classCount)
    }

    private fun fromPomProperties(contents: JarContents): List<DependencyIdentity> =
        contents.pomProperties
            .mapNotNull { properties ->
                val artifactId = properties.getProperty("artifactId")?.trim()
                if (artifactId.isNullOrEmpty()) return@mapNotNull null
                DependencyIdentity(
                    properties.getProperty("groupId")?.trim()?.ifEmpty { null },
                    artifactId,
                    properties.getProperty("version")?.trim()?.ifEmpty { null },
                )
            }.distinctBy { it.groupId to it.artifactId }
            .sortedWith(compareBy({ it.groupId.orEmpty() }, { it.artifactId }))

    private fun manifestVersion(contents: JarContents): String? =
        contents.manifest
            ?.mainAttributes
            ?.getValue(Attributes.Name.IMPLEMENTATION_VERSION)
            ?.trim()
            ?.ifEmpty { null }

    fun fromFilename(fileName: String): DependencyIdentity {
        val stem = ARCHIVE_EXTENSION.replace(fileName, "")
        val match = VERSIONED_STEM.matchEntire(stem) ?: return DependencyIdentity(null, stem, null)
        return DependencyIdentity(null, match.groupValues[1], match.groupValues[2])
    }
}
