package dev.otherlode.dependencies

import dev.otherlode.agentLogger
import dev.otherlode.export.DependencyDiscoverySource
import dev.otherlode.registry.DependencyOrigin
import dev.otherlode.registry.DependencyRegistry
import java.io.FileNotFoundException
import java.lang.System.Logger.Level
import java.nio.file.Files
import java.nio.file.Path
import java.security.ProtectionDomain
import java.util.WeakHashMap
import java.util.jar.JarFile

internal class DependencyResolver(
    private val registry: DependencyRegistry,
    private val classifier: JarClassifier,
    private val readFlatJar: (Path) -> JarContents = ::readFlat,
    private val readNestedJar: (Path, String) -> JarContents = ::readNested,
) {
    private val log by agentLogger(DependencyResolver::class.java.name)
    private val byDomain = WeakHashMap<ProtectionDomain, Int>()
    private val byLocation = HashMap<String, Int>()

    @Synchronized
    fun resolve(domain: ProtectionDomain): Int? {
        val cached = byDomain[domain]
        if (cached != null) return cached.takeIf { it != NOT_A_DEPENDENCY }
        val location = domain.codeSource?.location
        val id = if (location == null) null else resolveLocation(location.toString())
        byDomain[domain] = id ?: NOT_A_DEPENDENCY
        return id
    }

    @Synchronized
    fun resolveLocation(location: String): Int? {
        val cached = byLocation.getOrPut(location) { compute(location) ?: NOT_A_DEPENDENCY }
        return cached.takeIf { it != NOT_A_DEPENDENCY }
    }

    private fun compute(location: String): Int? =
        try {
            when (val parsed = CodeSourceLocation.parse(location)) {
                is CodeSourceLocation.OnDisk -> resolveOnDisk(parsed.path)
                is CodeSourceLocation.InJar -> resolveInJar(parsed)
                CodeSourceLocation.Unsupported -> null
            }
        } catch (e: Exception) {
            log.log(Level.DEBUG, "otherlode: could not resolve $location to a dependency; its classes are not counted", e)
            null
        }

    private fun resolveOnDisk(path: Path): Int? {
        if (!Files.isRegularFile(path)) return null
        val origin = DependencyOrigin.FlatJar(path.toAbsolutePath())
        registry.idForOrigin(origin)?.let { return it }
        if (registry.isJudgedNotADependency(origin)) return null
        val contents = readFlatJar(path)
        return discovered(classifier.classifyFlat(contents, path.fileName.toString(), path.toAbsolutePath().toString(), origin))
    }

    private fun resolveInJar(location: CodeSourceLocation.InJar): Int? {
        if (location.isDirectory) return null
        val origin = DependencyOrigin.NestedJar(location.outerJar, location.entryName)
        registry.idForOrigin(origin)?.let { return it }
        if (registry.isJudgedNotADependency(origin)) return null
        val contents = readNestedJar(location.outerJar, location.entryName)
        val fileName = location.entryName.substringAfterLast('/')
        return discovered(
            classifier.classifyNested(contents, fileName, location.entryName, "${location.outerJar}!/${location.entryName}", origin),
        )
    }

    private fun discovered(listed: ListedDependency?): Int? {
        if (listed == null) return null
        registry.idForKey(DependencyRegistry.identityKey(listed.identities))?.let { return it }
        return registry.register(
            listed.identities,
            listed.identitySource,
            listed.location,
            DependencyDiscoverySource.LOAD,
            listed.classCount,
            listed.origin,
        )
    }

    private companion object {
        const val NOT_A_DEPENDENCY = -1

        fun readFlat(path: Path): JarContents = JarFile(path.toFile()).use(JarContents::of)

        fun readNested(
            outerJar: Path,
            entryName: String,
        ): JarContents =
            JarFile(outerJar.toFile()).use { jar ->
                val entry = jar.getJarEntry(entryName) ?: throw FileNotFoundException("$outerJar!/$entryName")
                NestedJarReader.read(jar, entry)
            }
    }
}
