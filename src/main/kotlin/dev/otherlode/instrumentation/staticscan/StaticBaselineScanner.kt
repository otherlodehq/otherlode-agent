package dev.otherlode.instrumentation.staticscan

import dev.otherlode.bootLogger
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
 * code. [flatJarClassNames] is every class name whose kept copy is at the root of a jar, which may
 * be the adopter's own jar or a dependency on a flat classpath. Both are kept only to filter the scan's
 * references and are never sent. [externalClasses] maps the referenced names the scan kept to their
 * dependency, or to absent; [StaticBaselineChunker] sends each with the first chunk that references it.
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
 * Builds a load-independent inventory of what exists on the classpath under
 * [includePackages], by reading bytecode directly instead of waiting for the JVM to
 * load it.
 *
 * Applies the exact same [TypeMatchPolicy] a loaded class would be matched against, so a class
 * this scanner declares as dead-code-eligible is one the reactive tier would also have
 * instrumented, had it loaded. A class the reactive tier would match but never register, because
 * it has no concrete method to probe, lands in [StaticScanResult.unprobedClasses] rather than
 * being declared: declaring it would invite a collector to report it "never loaded" when the
 * agent had nothing to say about it.
 *
 * [excludePackages] is threaded through to [TypeMatchPolicy] the same way
 * [includePackages] is, so a class excluded from the reactive tier never lands in any
 * bucket of the baseline either.
 *
 * [supportingTypesLocator] resolves types a scanned class refers to but its own root does not
 * contain, such as an annotation's own class; see [withSupportingTypesFallback].
 */
class StaticBaselineScanner(
    private val includePackages: List<String>,
    private val excludePackages: List<String> = emptyList(),
    private val supportingTypesLocator: ClassFileLocator = ClassFileLocator.ForClassLoader.ofSystemLoader(),
) {
    private val log by lazy { bootLogger(StaticBaselineScanner::class.java.name) }
    private val typeNameMatcher = TypeMatchPolicy.typeNameMatcher(includePackages, excludePackages)

    private class Buckets {
        /** One parsed-table cache per scan: a class many others reference is parsed once, not once per referencing class. */
        val tableCache = BranchSiteAnalyzer.CrossClassTableCache(SCAN_TABLE_CACHE_ENTRIES)
        val declared = mutableListOf<DeclaredClass>()
        val unreadable = mutableListOf<UnreadableClass>()
        val unprobed = mutableListOf<UnprobedClass>()
        val ownClassNames = HashSet<String>()
        val flatJarClassNames = HashSet<String>()

        fun toResult() = StaticScanResult(declared, unreadable, unprobed, ownClassNames, flatJarClassNames)
    }

    /**
     * One place a class loader looks for classes: a directory, the root of a jar, or the
     * `BOOT-INF/classes/` or `WEB-INF/classes/` folder of a jar. [classNames] are the dotted names
     * of the classes it holds. [unreadNames] are names the JVM finds here whose bytes this scan does
     * not read, so the names are taken and nothing is classified for them: the classes of a named
     * package in a jar whose manifest cannot be read. [ownCode] is true for a directory
     * and a nested classes folder, and [nested] for the latter.
     */
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

    /**
     * The sources in the order the JVM searches them. The system class loader searches [flat], the
     * directories and jar roots of the classpath, and a Spring Boot launcher's class loader asks it
     * first and searches [nested] only after it. A class name belongs to the first source that
     * holds it.
     */
    private class SearchPath(
        val flat: List<ClassSource>,
        val nested: List<ClassSource>,
    ) {
        private val flatHolders = holders(flat)
        private val nestedHolders = holders(nested)

        val sources: List<ClassSource> get() = flat + nested

        /** The source whose copy of [className] the loader of [from] loads, or null when no source holds the name. */
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
     * `Class-Path` attribute. Only the copy of a class name the JVM loads is kept, which is the
     * first one in its search order; a later copy lands in no list of the result. The flat roots
     * come first, in classpath order, and the nested `BOOT-INF/classes/` and `WEB-INF/classes/`
     * folders of jars after all of them.
     * A `java -jar app.jar` launch puts only `app.jar` on `java.class.path`; the dependencies the
     * launcher actually loads are named in its manifest, relative to the jar's own directory, and
     * can name further jars with manifests of their own. Without following them, an adopter's
     * code in one of those jars would never be declared.
     *
     * Which files are read, at which paths and in what order is [ClasspathWalker]'s, as the JDK's
     * `URLClassPath` reads them. A file that is not a directory is a jar whatever its extension.
     * A file that cannot be opened as a jar is left out with a WARNING. So is a jar the JDK
     * drops: one whose manifest cannot be read when its raw manifest names a `Class-Path` or says
     * `Multi-Release: true`, and one whose `Class-Path` has a token that is not a valid URL. The
     * files such a jar names are left out with it. A jar whose manifest cannot be read and that the
     * JDK keeps contributes the classes of the unnamed package, and the other class names it holds
     * are taken: the JVM finds them here, cannot define them, and never reaches a later copy.
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

    /**
     * The sources of everything [ClasspathWalker] reaches from [roots], in the order it reaches
     * them. The jars stay open in [jarFiles], for the caller to close.
     */
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

    /**
     * A directory's classes. With [nested] it is the `BOOT-INF/classes/` or `WEB-INF/classes/`
     * folder of an exploded application, which a Spring Boot launcher searches after the flat
     * roots. [skip] takes a `/`-separated path under [root] and is true for one to leave out.
     */
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

    /**
     * The sources a jar holds: its own root, and the nested classes folder a Spring Boot executable
     * jar or a WAR packs the deployed application's own code in, `BOOT-INF/classes/` or
     * `WEB-INF/classes/`. Each dependency there is its own jar-inside-a-jar under
     * `BOOT-INF/lib`/`WEB-INF/lib`, deliberately not opened here.
     *
     * A multi-release jar's class is read from the highest `META-INF/versions/N/` copy with N at or
     * below the running JVM's feature version, and from the base entry when it has none, as
     * `JarFile.getVersionedEntry` picks it. A class held only under a versioned folder is a class
     * of the jar. A Spring Boot launcher reads the nested folders as plain folders of classes, so no
     * versioned entry there counts.
     *
     * With [manifestReadable] false, the jar declares the classes of the unnamed package, and the
     * names of the others are taken without being read, since the JVM cannot define them. Its nested
     * classes folders are left out, since a Spring Boot launcher cannot read its manifest.
     */
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

    /** Reads a resource by its path under the directory [root], the leading bytes of it at most. */
    private fun folderResources(root: File): (String) -> ByteArray? =
        { path ->
            File(root, path).takeIf { it.isFile }?.inputStream()?.use { it.readNBytes(ScalaReleases.HEADER_BYTES) }
        }

    /**
     * Reads a resource by its path under [prefix] inside [jarFile], the leading bytes of it at most,
     * from the highest of [versions] that holds it, else from the base entry.
     */
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
            // Covers a corrupt class file, or a failure resolving a supporting type (e.g. an
            // annotation's own definition) while describing this one. Either way, this class
            // could not be safely classified, so it is reported rather than silently dropped.
            buckets.unreadable += UnreadableClass(className, e.message ?: e.toString())
        }
    }

    /**
     * [DeclaredMethod]s for one class, plus the supertypes, class-level references, source file,
     * body kind and Kotlin kind read from the same analysis pass.
     */
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

    /**
     * Reads [className]'s bytes through [locator] once, ahead of filtering: whether a synthetic
     * method is a probed lambda body depends on whether the class carries a `Scala`/`ScalaSig`
     * attribute ([ScalaClassDetector]), and the same bytes are also used to detect inline
     * functions with the same LocalVariableTable rule [BranchSiteAnalyzer] uses at transform time,
     * merged into each declared method, to detect a `<clinit>` of the class's own, to read its
     * in-scope call edges, its out-of-scope references and its lambda bodies, and to read its
     * superclass, interfaces, source file and body kind. References are listed as the analyser
     * records them, JDK names included; [BaselineReferenceFilter] drops those before the baseline
     * is sent.
     *
     * A class whose bytes cannot be resolved here is not itself unreadable: its [TypeDescription]
     * already resolved successfully through [pool][TypePool], so it is still declared, just
     * treated as a non-Scala class, with every method's [DeclaredMethod.inline] and
     * [DeclaredMethod.calls] and references left empty, no method marked as a lambda body,
     * [DeclaredClass.superClassName], [DeclaredClass.sourceFile] and [DeclaredClass.sourceName]
     * left null, [DeclaredClass.bodyKind] left [BodyKind.NONE], [DeclaredClass.kotlinKind] left
     * [KotlinKind.NONE], no `<clinit>` entry added, and
     * [DeclaredClass.interfaceNames] left empty. This can only happen if the two disagree about
     * what is readable, which no locator this scanner builds does.
     *
     * A `<clinit>` entry is appended after every other declared method, mirroring
     * [dev.otherlode.instrumentation.OtherlodeInstrumentation]'s own placement of the
     * type initializer's probe after every other slot category. This can make the returned list
     * non-empty even when [TypeMatchPolicy.methodMatcher] admits none of the type's own methods,
     * so a class whose only probe-worthy content is its own static initializer is still declared
     * rather than reported as unprobed.
     */
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
        // A resolved Scala default getter takes the mark of the method it fills a default for, as
        // its omission probe does in the manifest, so `copy$default$1` is generated with `copy`.
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

    /**
     * Resolves another class's bytes by internal name from the copy the JVM loads for the class
     * being scanned in [from]: the first source of [search] that holds the name, as [from]'s class
     * loader searches them. A name no source holds, or one its holder lists as unread, is read
     * through [supportingTypesLocator]. [BranchSiteAnalyzer] uses
     * this to resolve a cross-class Kotlin `$default` pass-through to its real target, the same
     * mechanism [dev.otherlode.instrumentation.OtherlodeInstrumentation] uses at transform time; a
     * baseline edge and a manifest edge must agree on the target, not one naming `$default` and
     * the other naming the function it fills in for. Any failure, including a class no locator
     * can find, is swallowed and reported as an unresolved pass-through rather than as a scan
     * failure.
     */
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

    /** Cheap, string-only pre-filter, applied before resolving a [TypeDescription] at all. */
    private fun looksInScope(className: String): Boolean = TypeMatchPolicy.isIncluded(className, includePackages, excludePackages)

    /**
     * A root's own locator only has the bytes for classes physically inside that root. Resolving
     * a type can still need a supporting type's own bytecode, such as a supertype that lives in a
     * library jar elsewhere on the classpath. Falling back to [supportingTypesLocator] (the system
     * classloader by default) resolves that without ever loading the type actually being scanned:
     * [ClassFileLocator.ForClassLoader] reads bytecode as a classloader resource, the same as any
     * other locator here, rather than calling `Class.forName`.
     *
     * Every pool built over such a locator resolves lazily: a type it cannot find still answers
     * to its name, and only fails when a member or annotation is asked for. The type matcher's
     * continuation-class check reads a superclass's name alone, so a class whose superclass sits
     * in a dependency jar this scan never opens (the Kotlin stdlib under `BOOT-INF/lib`) is still
     * classified correctly. The agent's own transform path already uses ByteBuddy's default lazy
     * pool strategy.
     */
    private fun withSupportingTypesFallback(locator: ClassFileLocator): ClassFileLocator =
        ClassFileLocator.Compound(locator, supportingTypesLocator)

    private companion object {
        val NESTED_CLASSES_PREFIXES = listOf("BOOT-INF/classes/", "WEB-INF/classes/")
        val NESTED_JAR_PREFIXES = listOf("BOOT-INF/lib/", "WEB-INF/lib/")

        /** The lowest `META-INF/versions/N/` the JDK's `JarFile.getVersionedEntry` reads, `BASE_VERSION_FEATURE`. A runtime version at or below it reads none. */
        const val MIN_VERSIONED_RELEASE = 8

        /**
         * Tables held at once during a scan. Every in-scope class another one references is parsed
         * once while it stays within this many most recently used; a scan larger than this parses
         * the least recently referenced again rather than holding a whole classpath's tables.
         */
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

/**
 * Whether a path inside a classpath root names a class. A `.class` file under `META-INF/` is never
 * one: a multi-release jar keeps its per-JDK variants under `META-INF/versions/N/`, and the JVM
 * loads those under the same name as the base entry, so counting the entry by its path would
 * invent a second, phantom class named `META-INF.versions.9.com.acme.Foo`. A class present only in
 * a versioned directory is therefore not counted at all, which can only lose a class, never invent
 * one. `module-info` and `package-info` carry no methods and are not types an adopter's code refers
 * to, so they are left out too.
 *
 * Shared by the static baseline scan, the dependency listing and the class listing, so all three
 * count a jar's classes by one rule.
 */
internal fun isClassEntry(path: String): Boolean {
    if (!path.endsWith(".class") || path.startsWith("META-INF/")) return false
    val simpleName = path.substringAfterLast('/').removeSuffix(".class")
    return simpleName != "module-info" && simpleName != "package-info"
}

/**
 * The path of a multi-release jar's versioned class entry after its `META-INF/versions/N/` prefix,
 * or null when [path] is not one. The JVM loads such an entry when it opens the jar for a runtime
 * of version N or later, so a class can exist only there.
 */
internal fun versionedClassPath(path: String): String? {
    val match = VERSIONED_ENTRY.matchEntire(path) ?: return null
    val inner = match.groupValues[2]
    return if (isClassEntry(inner)) inner else null
}

/** A multi-release jar's class entry: [classPath] is its path after `META-INF/versions/[version]/`. */
internal class VersionedClassEntry(
    val version: Int,
    val classPath: String,
)

/**
 * [path] as a [VersionedClassEntry], or null when it is not one or its directory is not a plain
 * decimal number: the JDK looks for `META-INF/versions/9/`, never `META-INF/versions/09/`.
 */
internal fun versionedClassEntry(path: String): VersionedClassEntry? {
    val classPath = versionedClassPath(path) ?: return null
    val digits = VERSIONED_ENTRY.matchEntire(path)?.groupValues?.get(1) ?: return null
    if (digits.startsWith('0')) return null
    return digits.toIntOrNull()?.let { VersionedClassEntry(it, classPath) }
}

private val VERSIONED_ENTRY = Regex("^META-INF/versions/([0-9]+)/(.+)$")

/**
 * The entry for [path] under [prefix] in this jar: the copy under the highest of [versions] that
 * holds it, else the base entry. A path in `META-INF/` has no versioned copy.
 */
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

/**
 * Reads a class's bytes from a fixed prefix inside [jarFile], for nested classpath roots such as
 * `BOOT-INF/classes/`, and from the highest of [versions] that holds the class when there are any.
 */
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
        // jarFile's lifecycle is owned by the caller, which may share it across multiple locators.
    }
}
