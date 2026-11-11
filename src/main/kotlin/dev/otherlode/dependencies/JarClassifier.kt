package dev.otherlode.dependencies

import dev.otherlode.agentLogger
import dev.otherlode.instrumentation.TypeMatchPolicy
import dev.otherlode.registry.DependencyOrigin
import java.lang.System.Logger.Level
import java.util.jar.Attributes
import java.util.jar.Manifest

internal class JarClassifier(
    private val includes: List<String>,
    private val excludes: List<String>,
) {
    private val log by agentLogger(StartupClasspathLister::class.java.name)

    enum class Kind {
        AGENT,

        BOOT_APPLICATION,

        ORDINARY,
    }

    fun isBootApplication(manifest: Manifest?): Boolean {
        val attributes = manifest?.mainAttributes ?: return false
        return BOOT_ATTRIBUTES.any { attributes.getValue(it) != null }
    }

    fun kindOf(manifest: Manifest?): Kind {
        val attributes = manifest?.mainAttributes ?: Attributes()
        return when {
            AGENT_ATTRIBUTES.any { attributes.getValue(it) != null } -> Kind.AGENT
            BOOT_ATTRIBUTES.any { attributes.getValue(it) != null } -> Kind.BOOT_APPLICATION
            else -> Kind.ORDINARY
        }
    }

    fun classifyFlat(
        contents: JarContents,
        fileName: String,
        location: String,
        origin: DependencyOrigin,
    ): ListedDependency? {
        if (kindOf(contents.manifest) != Kind.ORDINARY) return null
        return classifyNested(contents, fileName, location, location, origin)
    }

    fun classifyNested(
        contents: JarContents,
        fileName: String,
        location: String,
        displayName: String,
        origin: DependencyOrigin,
    ): ListedDependency? {
        if (contents.classNames.any { it.startsWith(TypeMatchPolicy.AGENT_PACKAGE_PREFIX) }) return null
        if (isAdoptersOwn(contents, displayName)) return null
        val identity = DependencyIdentityReader.identify(contents, fileName)
        return ListedDependency(identity.identities, identity.identitySource, location, identity.classCount, origin, contents.classNames)
    }

    private fun isAdoptersOwn(
        contents: JarContents,
        displayName: String,
    ): Boolean {
        val outside = contents.classNames.count { !TypeMatchPolicy.isIncluded(it, includes, excludes) }
        if (outside == contents.classNames.size) return false
        if (outside > 0) {
            log.log(
                Level.INFO,
                "otherlode: $displayName holds classes under the include rules, so it is treated as the adopter's own; " +
                    "its $outside classes outside the include rules are not reported as a dependency",
            )
        }
        return true
    }

    private companion object {
        val AGENT_ATTRIBUTES = listOf("Premain-Class", "Launcher-Agent-Class")
        val BOOT_ATTRIBUTES = listOf("Spring-Boot-Classpath-Index", "Spring-Boot-Lib")
    }
}
