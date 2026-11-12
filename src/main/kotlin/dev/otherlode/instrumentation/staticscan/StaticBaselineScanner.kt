package dev.otherlode.instrumentation.staticscan

import dev.otherlode.agentLogger
import dev.otherlode.dependencies.ClasspathVisitor
import dev.otherlode.dependencies.ClasspathWalker
import dev.otherlode.dependencies.JarClassifier
import dev.otherlode.dependencies.bootLibPrefixes
import dev.otherlode.export.BodyKind
import dev.otherlode.export.DeclaredClass
import dev.otherlode.export.DeclaredMethod
import dev.otherlode.export.ExternalClass
import dev.otherlode.export.KotlinKind
import dev.otherlode.export.UnprobedClass
import dev.otherlode.export.UnreadableClass
import dev.otherlode.instrumentation.JpaEnhancement
import dev.otherlode.instrumentation.ScalaClassDetector
import dev.otherlode.instrumentation.SubroutineInliner
import dev.otherlode.instrumentation.TypeMatchPolicy
import dev.otherlode.instrumentation.branch.BranchSiteAnalyzer
import dev.otherlode.instrumentation.branch.ScalaReleases
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.pool.TypePool
import java.io.File
import java.io.IOException
import java.lang.System.Logger.Level
import java.util.jar.JarFile
import java.util.jar.Manifest
import java.util.zip.ZipEntry

/**
 * What one static baseline scan found. [ownClassNames] is every class name whose kept copy is in a
 * directory root or under `BOOT-INF/classes`/`WEB-INF/classes`, in scope or not: the adopter's own
 * code. [flatJarClassNames] is every class name whose kept copy is at the root of a jar. Both only
 * filter the scan's references and are never sent. [externalClasses] maps the referenced names the
 * scan kept to their dependency, or to absent.
 */
data class StaticScanResult(
    val declaredClasses: List<DeclaredClass>,
    val unreadableClasses: List<UnreadableClass>,
    val unprobedClasses: List<UnprobedClass> = emptyList(),
    val ownClassNames: Set<String> = emptySet(),
    val flatJarClassNames: Set<String> = emptySet(),
    val externalClasses: List<ExternalClass> = emptyList(),
) {
    /** Every class name the scan saw, in any bucket. */
    fun allClassNames(): Set<String> =
        buildSet {
            declaredClasses.mapTo(this) { it.className }
            unreadableClasses.mapTo(this) { it.className }
            unprobedClasses.mapTo(this) { it.className }
        }

    /** This result with every method's and every class's reference list emptied. */
    fun withoutReferences(): StaticScanResult =
        copy(
            declaredClasses =
                declaredClasses.map { declared ->
                    declared.copy(
                        methods = declared.methods.map { it.copy(referencedClasses = emptyList()) },
                        referencedClasses = emptyList(),
                    )
                },
        )
}

/**
 * Builds a load-independent inventory of what exists on the classpath under [includePackages].
 *
 * Applies the same [TypeMatchPolicy] a loaded class would be matched against. A class the reactive
 * tier would match but never register, because it has no concrete method to probe, lands in
 * [StaticScanResult.unprobedClasses] rather than being declared. A class excluded by
 * [excludePackages] lands in no bucket.
 *
 * [supportingTypesLocator] resolves types a scanned class refers to but its own root does not
 * contain, such as an annotation's own class.
 */
class StaticBaselineScanner(
    private val includePackages: List<String>,
    private val excludePackages: List<String> = emptyList(),
    private val supportingTypesLocator: ClassFileLocator = ClassFileLocator.ForClassLoader.ofSystemLoader(),
) {
    private val log by agentLogger(StaticBaselineScanner::class.java.name)
    private val typeNameMatcher = TypeMatchPolicy.typeNameMatcher(includePackages, excludePackages)

    private class Buckets {
        val tableCache = BranchSiteAnalyzer.CrossClassTableCache(SCAN_TABLE_CACHE_ENTRIES)
        val declared = mutableListOf<DeclaredClass>()
        val unreadable = mutableListOf<UnreadableClass>()
        val unprobed = mutableListOf<UnprobedClass>()
        val ownClassNames = HashSet<String>()
        val flatJarClassNames = HashSet<String>()

        fun toResult() = StaticScanResult(declared, unreadable, unprobed, ownClassNames, flatJarClassNames)
    }

    private class ClassSource(
        val origin: File,
        val ownCode: Boolean,
        val nested: Boolean,
        val ownLocator: ClassFileLocator,
        val locator: ClassFileLocator,
        val resources: (String) -> ByteArray?,
        val classNames: Set<String>,
        val unreadNames: Set<String>,
    )

    private class SearchPath(
        val flat: List<ClassSource>,
        val nested: List<ClassSource>,
    ) {
        private val flatHolders = holders(flat)
        private val nestedHolders = holders(nested)

        val sources: List<ClassSource> get() = flat + nested

        fun holderOf(
            className: String,
            from: ClassSource,
        ): ClassSource? = flatHolders[className] ?: if (from.nested) nestedHolders[className] else null

        private fun holders(sources: List<ClassSource>): Map<String, ClassSource> {
            val holders = HashMap<String, ClassSource>()
            for (source in sources) {
                for (className in source.classNames) holders.putIfAbsent(className, source)
                for (className in source.unreadNames) holders.putIfAbsent(className, source)
            }
            return holders
        }
    }

    /**
     * Scans [classpathRoots] plus every jar and directory they reach through a manifest
     * `Class-Path` attribute, as [ClasspathWalker] reads them. Only the copy of a class name the
     * JVM loads is kept, which is the first one in its search order; a later copy lands in no list
     * of the result. The flat roots come first, in classpath order, and the nested
     * `BOOT-INF/classes/` and `WEB-INF/classes/` folders of jars after all of them.
     *
     * A file that cannot be opened as a jar is left out with a WARNING. So is a jar the JDK drops:
     * one whose manifest cannot be read when its raw manifest names a `Class-Path` or says
     * `Multi-Release: true`, and one whose `Class-Path` has a token that is not a valid URL. The
     * files such a jar names are left out with it. A jar whose manifest cannot be read and that the
     * JDK keeps contributes the classes of the unnamed package, and the other class names it holds
     * are taken without being read.
     */
    fun scan(classpathRoots: List<File> = defaultClasspathRoots()): StaticScanResult {
        val buckets = Buckets()
        val jarFiles = mutableListOf<JarFile>()
        try {
            val search = searchPath(classpathRoots, jarFiles)
            for (source in search.sources) {
                try {
                    val pool = TypePool.Default.WithLazyResolution.of(source.locator)
                    for (className in source.classNames) {
                        if (search.holderOf(className, source) !== source) continue
                        if (source.ownCode) buckets.ownClassNames += className else buckets.flatJarClassNames += className
                        classify(className, source, pool, search, buckets)
                    }
                } catch (e: Exception) {
                    log.log(Level.WARNING, "otherlode: could not scan classpath entry ${source.origin} for the static baseline", e)
                }
            }
        } finally {
            jarFiles.forEach { runCatching { it.close() } }
        }
        return buckets.toResult()
    }

    private fun searchPath(
        roots: List<File>,
        jarFiles: MutableList<JarFile>,
    ): SearchPath {
        val flat = mutableListOf<ClassSource>()
        val nested = mutableListOf<ClassSource>()
        val visitor =
            object : ClasspathVisitor {
                override fun jar(
                    jar: File,
                    jarFile: JarFile,
                ) = add(jar, manifestReadable = true)

                override fun jarWithUnreadableManifest(
                    jar: File,
                    jarFile: JarFile,
                    cause: Exception,
                ) {
                    log.log(
                        Level.WARNING,
                        "otherlode: $jar has a manifest that could not be read, so the static baseline declares only its unnamed-package classes",
                        cause,
                    )
                    add(jar, manifestReadable = false)
                }

                override fun directory(directory: File) {
                    flat += directorySource(directory)
                }

                override fun directoryWithUnreadableManifest(
                    directory: File,
                    cause: Exception,
                ) {
                    flat += directorySource(directory)
                }

                override fun libraryDirectory(
                    application: File,
                    directory: File,
                ) = Unit

                override fun explodedApplication(
                    root: File,
                    manifest: Manifest,
                ) {
                    val libraryFolders = bootLibPrefixes(manifest.mainAttributes)
                    val applicationFolders = NESTED_CLASSES_PREFIXES + libraryFolders
                    flat += directorySource(root, skip = { path -> applicationFolders.any { path.startsWith(it) } })
                    for (prefix in NESTED_CLASSES_PREFIXES) {
                        val classes = File(root, prefix)
                        if (classes.isDirectory) nested += directorySource(classes, nested = true)
                    }
                }

                override fun libraryJar(
                    application: File,
                    jar: File,
                    jarFile: JarFile,
                ) = Unit

                override fun unreadable(
                    location: String,
                    cause: Exception,
                ) {
                    log.log(Level.WARNING, "otherlode: left $location out of the static baseline: it could not be read", cause)
                }

                private fun add(
                    jar: File,
                    manifestReadable: Boolean,
                ) {
                    val opened = JarFile(jar)
                    jarFiles += opened
                    for (source in jarSources(jar, opened, manifestReadable)) (if (source.nested) nested else flat) += source
                }
            }
        ClasspathWalker(JarClassifier(includePackages, excludePackages)).walk(roots.map { it.path }, visitor)
        return SearchPath(flat, nested)
    }

    private fun directorySource(
        root: File,
        nested: Boolean = false,
        skip: (String) -> Boolean = { false },
    ): ClassSource =
        classSource(
            origin = root,
            ownCode = true,
            nested = nested,
            ownLocator = ClassFileLocator.ForFolder(root),
            resources = folderResources(root),
            classNames = candidateClassNamesInFolder(root, skip).toCollection(LinkedHashSet()),
            unreadNames = emptySet(),
        )

    private fun jarSources(
        root: File,
        jarFile: JarFile,
        manifestReadable: Boolean,
    ): List<ClassSource> {
        val flatNames = LinkedHashSet<String>()
        val nestedNames = NESTED_CLASSES_PREFIXES.associateWith { LinkedHashSet<String>() }
        val versionedNames = LinkedHashSet<String>()
        val versions = sortedSetOf<Int>(Comparator.reverseOrder())
        val runtimeFeature = JarFile.runtimeVersion().feature()
        val readsVersions = runtimeFeature > MIN_VERSIONED_RELEASE && jarFile.isMultiRelease
        for (entry in jarFile.entries()) {
            if (entry.isDirectory) continue
            val name = entry.name
            val nestedPrefix = NESTED_CLASSES_PREFIXES.firstOrNull { name.startsWith(it) }
            when {
                nestedPrefix != null -> {
                    val relativeName = name.removePrefix(nestedPrefix)
                    if (isClassEntry(relativeName)) nestedNames.getValue(nestedPrefix) += dottedName(relativeName)
                }

                NESTED_JAR_PREFIXES.any { name.startsWith(it) } -> {}

                isClassEntry(name) -> {
                    flatNames += dottedName(name)
                }

                readsVersions -> {
                    val versioned = versionedClassEntry(name)
                    if (versioned != null && versioned.version in MIN_VERSIONED_RELEASE..runtimeFeature) {
                        versionedNames += dottedName(versioned.classPath)
                        versions += versioned.version
                    }
                }
            }
        }
        val rootNames = LinkedHashSet(flatNames).apply { addAll(versionedNames) }
        val declared = if (manifestReadable) rootNames else rootNames.filterTo(LinkedHashSet()) { '.' !in it }
        val sources = mutableListOf<ClassSource>()
        sources +=
            classSource(
                root,
                ownCode = false,
                nested = false,
                ownLocator = JarClassFileLocator(jarFile, "", versions.toList()),
                resources = jarResources(jarFile, "", versions.toList()),
                classNames = declared,
                unreadNames = rootNames - declared,
            )
        for ((prefix, names) in nestedNames) {
            if (names.isEmpty() || !manifestReadable) continue
            sources +=
                classSource(
                    root,
                    ownCode = true,
                    nested = true,
                    ownLocator = JarClassFileLocator(jarFile, prefix, emptyList()),
                    resources = jarResources(jarFile, prefix, emptyList()),
                    classNames = names,
                    unreadNames = emptySet(),
                )
        }
        return sources
    }

    private fun classSource(
        origin: File,
        ownCode: Boolean,
        nested: Boolean,
        ownLocator: ClassFileLocator,
        resources: (String) -> ByteArray?,
        classNames: Set<String>,
        unreadNames: Set<String>,
    ): ClassSource =
        ClassSource(
            origin,
            ownCode,
            nested,
            ownLocator,
            withSupportingTypesFallback(ownLocator),
            resources,
            classNames,
            unreadNames,
        )

    private fun dottedName(entryName: String): String = entryName.removeSuffix(".class").replace('/', '.')

    private fun folderResources(root: File): (String) -> ByteArray? =
        { path ->
            File(root, path).takeIf { it.isFile }?.inputStream()?.use { it.readNBytes(ScalaReleases.HEADER_BYTES) }
        }

    private fun jarResources(
        jarFile: JarFile,
        prefix: String,
        versions: List<Int>,
    ): (String) -> ByteArray? =
        { path ->
            jarFile.entryFor(prefix, path, versions)?.let { entry ->
                jarFile.getInputStream(entry).use { it.readNBytes(ScalaReleases.HEADER_BYTES) }
            }
        }

    private fun candidateClassNamesInFolder(
        root: File,
        skip: (String) -> Boolean,
    ): List<String> =
        root
            .walkTopDown()
            .filter { it.isFile && it.relativeTo(root).invariantSeparatorsPath.let { path -> isClassEntry(path) && !skip(path) } }
            .map {
                it
                    .relativeTo(root)
                    .path
                    .removeSuffix(".class")
                    .replace(File.separatorChar, '.')
            }.toList()

    private fun classify(
        className: String,
        source: ClassSource,
        pool: TypePool,
        search: SearchPath,
        buckets: Buckets,
    ) {
        if (!looksInScope(className)) return
        try {
            val resolution = pool.describe(className)
            if (!resolution.isResolved) {
                buckets.unreadable += UnreadableClass(className, "class file could not be resolved")
                return
            }
            val typeDescription = resolution.resolve()
            if (!typeNameMatcher.matches(typeDescription)) return
            val scanned = declaredMethodsOf(typeDescription, className, source, search, buckets.tableCache)
            if (scanned.methods.isEmpty()) {
                buckets.unprobed += UnprobedClass(className, "no concrete methods to probe")
                return
            }
            buckets.declared +=
                DeclaredClass(
                    className,
                    scanned.methods,
                    scanned.superClassName,
                    scanned.interfaceNames,
                    scanned.classReferences,
                    scanned.sourceFile,
                    scanned.bodyKind,
                    scanned.sourceName,
                    scanned.kotlinKind,
                )
        } catch (e: Exception) {
            buckets.unreadable += UnreadableClass(className, e.message ?: e.toString())
        }
    }

    private class ScannedMethods(
        val methods: List<DeclaredMethod>,
        val superClassName: String?,
        val interfaceNames: List<String>,
        val classReferences: List<String>,
        val sourceFile: String?,
        val bodyKind: BodyKind,
        val sourceName: String?,
        val kotlinKind: KotlinKind,
    )

    private fun declaredMethodsOf(
        typeDescription: TypeDescription,
        className: String,
        source: ClassSource,
        search: SearchPath,
        tableCache: BranchSiteAnalyzer.CrossClassTableCache,
    ): ScannedMethods {
        val classBytes =
            try {
                val resolution = source.locator.locate(className)
                if (resolution.isResolved) SubroutineInliner.inline(resolution.resolve()) else null
            } catch (_: IOException) {
                null
            }
        val isScalaClass = classBytes?.let(ScalaClassDetector::isScalaClass) ?: false
        val enhancement = classBytes?.let(JpaEnhancement::of) ?: JpaEnhancement.NONE
        val methods =
            typeDescription.declaredMethods
                .filter(TypeMatchPolicy.methodMatcher(isScalaClass))
                .filterNot { enhancement.isUnprobed(it.internalName, it.descriptor) }
        val eligible = methods.map { it.internalName to it.descriptor }.toSet()
        val analysis =
            if (classBytes != null) {
                BranchSiteAnalyzer.analyze(
                    classBytes,
                    crossClassLookup(source, search),
                    includePackages,
                    excludePackages,
                    tableCache,
                    resourceLookup = source.resources,
                ) { name, descriptor -> (name to descriptor) in eligible }
            } else {
                BranchSiteAnalyzer.Analysis.EMPTY
            }
        val getterTargets = analysis.scalaGetterSites.associateBy { it.getterName to it.getterDescriptor }
        val declaredMethods =
            methods.map {
                val sourceSignature = analysis.sourceSignatureOf(it.internalName, it.descriptor)
                val getterTarget = getterTargets[it.internalName to it.descriptor]
                DeclaredMethod(
                    analysis.reportedNameOf(it.internalName, it.descriptor),
                    it.descriptor,
                    analysis.isInline(it.internalName, it.descriptor),
                    analysis.callsOf(it.internalName, it.descriptor),
                    if (getterTarget != null) {
                        analysis.generatedBy(getterTarget.targetName, getterTarget.targetDescriptor)
                    } else {
                        analysis.generatedBy(it.internalName, it.descriptor)
                    },
                    analysis.referencesOf(it.internalName, it.descriptor),
                    analysis.isLambdaBody(it.internalName, it.descriptor),
                    analysis.branchSitesOf(it.internalName, it.descriptor),
                    it.isStatic,
                    sourceSignature.parameterNames,
                    sourceSignature.genericSignature,
                    sourceSignature.extensionReceiver,
                    if (getterTarget != null) {
                        analysis.unreadShape(getterTarget.targetName, getterTarget.targetDescriptor)
                    } else {
                        analysis.unreadShape(it.internalName, it.descriptor)
                    },
                )
            }
        val typeInitializer =
            if (analysis.hasTypeInitializer) {
                listOf(
                    DeclaredMethod(
                        "<clinit>",
                        "()V",
                        calls = analysis.callsOf("<clinit>", "()V"),
                        referencedClasses = analysis.referencesOf("<clinit>", "()V"),
                    ),
                )
            } else {
                emptyList()
            }
        return ScannedMethods(
            declaredMethods + typeInitializer,
            analysis.superClassName,
            analysis.interfaceNames,
            analysis.classReferences,
            analysis.sourceFile,
            analysis.bodyKind,
            analysis.sourceName,
            analysis.kotlinKind,
        )
    }

    private fun crossClassLookup(
        from: ClassSource,
        search: SearchPath,
    ): (String) -> ByteArray? =
        { internalName ->
            try {
                val className = internalName.replace('/', '.')
                val holder = search.holderOf(className, from)
                val locator = if (holder == null || className in holder.unreadNames) supportingTypesLocator else holder.ownLocator
                val resolution = locator.locate(className)
                if (resolution.isResolved) resolution.resolve() else null
            } catch (_: Exception) {
                null
            }
        }

    private fun looksInScope(className: String): Boolean = TypeMatchPolicy.isIncluded(className, includePackages, excludePackages)

    private fun withSupportingTypesFallback(locator: ClassFileLocator): ClassFileLocator =
        ClassFileLocator.Compound(locator, supportingTypesLocator)

    private companion object {
        val NESTED_CLASSES_PREFIXES = listOf("BOOT-INF/classes/", "WEB-INF/classes/")
        val NESTED_JAR_PREFIXES = listOf("BOOT-INF/lib/", "WEB-INF/lib/")

        const val MIN_VERSIONED_RELEASE = 8

        const val SCAN_TABLE_CACHE_ENTRIES = 8192

        fun defaultClasspathRoots(): List<File> =
            System
                .getProperty("java.class.path")
                .orEmpty()
                .split(File.pathSeparator)
                .filter { it.isNotEmpty() }
                .map { File(it) }
    }
}

internal fun isClassEntry(path: String): Boolean {
    if (!path.endsWith(".class") || path.startsWith("META-INF/")) return false
    val simpleName = path.substringAfterLast('/').removeSuffix(".class")
    return simpleName != "module-info" && simpleName != "package-info"
}

internal fun versionedClassPath(path: String): String? {
    val match = VERSIONED_ENTRY.matchEntire(path) ?: return null
    val inner = match.groupValues[2]
    return if (isClassEntry(inner)) inner else null
}

internal class VersionedClassEntry(
    val version: Int,
    val classPath: String,
)

internal fun versionedClassEntry(path: String): VersionedClassEntry? {
    val classPath = versionedClassPath(path) ?: return null
    val digits = VERSIONED_ENTRY.matchEntire(path)?.groupValues?.get(1) ?: return null
    if (digits.startsWith('0')) return null
    return digits.toIntOrNull()?.let { VersionedClassEntry(it, classPath) }
}

private val VERSIONED_ENTRY = Regex("^META-INF/versions/([0-9]+)/(.+)$")

private fun JarFile.entryFor(
    prefix: String,
    path: String,
    versions: List<Int>,
): ZipEntry? {
    if (!path.startsWith("META-INF/")) {
        for (version in versions) {
            getEntry("${prefix}META-INF/versions/$version/$path")?.let { return it }
        }
    }
    return getEntry(prefix + path)
}

private class JarClassFileLocator(
    private val jarFile: JarFile,
    private val prefix: String,
    private val versions: List<Int>,
) : ClassFileLocator {
    override fun locate(typeName: String): ClassFileLocator.Resolution {
        val entry =
            jarFile.entryFor(prefix, typeName.replace('.', '/') + ".class", versions)
                ?: return ClassFileLocator.Resolution.Illegal(typeName)
        val bytes = jarFile.getInputStream(entry).use { it.readBytes() }
        return ClassFileLocator.Resolution.Explicit(bytes)
    }

    override fun close() {
        // The caller owns jarFile and may share it across locators.
    }
}
