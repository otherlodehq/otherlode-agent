package dev.otherlode.benchmark

import dev.otherlode.export.ResourceAttributes
import dev.otherlode.registry.ProbeRegistry
import java.io.File
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Weaves every class of the five benchmark corpora twice, once with the placeholder pool and once
 * with ByteBuddy's own, and compares. The placeholder pool hooks ByteBuddy's type pool internals, so
 * an upgrade that breaks the hook would change what the agent writes without any compile error; this
 * is where that shows. Every class that weaves without placeholders must weave to the same bytes
 * with them, and the classes that failed on an absent type weave.
 */
class PlaceholderCorpusTest {
    private class Sweep(
        val corpus: String,
        val classes: Int,
        val woven: Int,
        val plainWoven: Int,
        val different: List<String>,
        val recovered: List<String>,
        val unexplained: List<String>,
        val plainNanos: Long,
        val placeholderNanos: Long,
    )

    private fun weaveAll(corpusName: String): Sweep {
        val corpus = BenchmarkCorpus.load(corpusName)
        var woven = 0
        var plainWoven = 0
        var plainNanos = 0L
        var placeholderNanos = 0L
        val different = mutableListOf<String>()
        val recovered = mutableListOf<String>()
        val unexplained = mutableListOf<String>()
        for (set in corpus.classSets) {
            val classpath =
                checkNotNull(
                    System.getProperty("$CLASSPATH_PROPERTY$corpusName.${set.name}"),
                ) { "no classpath for $corpusName.${set.name}" }
            val corpusPath = checkNotNull(System.getProperty("${BenchmarkCorpus.PROPERTY_PREFIX}$corpusName.${set.name}"))
            val urls =
                (corpusPath.split(File.pathSeparator) + classpath.split(File.pathSeparator))
                    .filter { it.isNotEmpty() }
                    .map { File(it).toURI().toURL() }
            URLClassLoader(urls.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
                val plain = HotPathWeaver.offlineTransformer(corpus.includePackages, ProbeRegistry(), describeMissingTypes = false)
                val placeholder = HotPathWeaver.offlineTransformer(corpus.includePackages, ProbeRegistry())
                for ((internalName, original) in set.classes) {
                    val plainStarted = System.nanoTime()
                    val plainResult = runCatching { plain.transform(loader, internalName, null, null, original) }
                    plainNanos += System.nanoTime() - plainStarted
                    val placeholderStarted = System.nanoTime()
                    val placeholderResult = runCatching { placeholder.transform(loader, internalName, null, null, original) }
                    placeholderNanos += System.nanoTime() - placeholderStarted
                    val plainBytes = plainResult.getOrNull()
                    val placeholderBytes = placeholderResult.getOrNull()
                    if (placeholderBytes != null) woven++
                    if (plainBytes != null) {
                        plainWoven++
                        if (placeholderBytes == null ||
                            !plainBytes.contentEquals(placeholderBytes)
                        ) {
                            different += "${set.name}:$internalName"
                        }
                    } else if (plainResult.isFailure) {
                        val reason =
                            generateSequence(
                                plainResult.exceptionOrNull(),
                            ) { it.cause }.joinToString(" <- ") { it.message.orEmpty() }
                        val absentType = "Cannot resolve type description" in reason
                        when {
                            placeholderBytes != null && absentType -> {
                                recovered += "${set.name}:$internalName"
                            }

                            placeholderBytes != null -> {
                                unexplained +=
                                    "${set.name}:$internalName woven with placeholders; plain failed: $reason"
                            }
                        }
                    }
                }
            }
        }
        return Sweep(
            corpusName,
            corpus.classCount,
            woven,
            plainWoven,
            different,
            recovered,
            unexplained,
            plainNanos,
            placeholderNanos,
        )
    }

    @Test
    fun `placeholders leave every other class's bytes identical and recover the classes that named an absent type`() {
        val results = CORPORA.map { weaveAll(it) }
        println(
            "placeholder corpus sweep: " +
                results.joinToString("; ") {
                    "${it.corpus} classes=${it.classes} plainWoven=${it.plainWoven} woven=${it.woven} recovered=${it.recovered.size} " +
                        "plainMs=${it.plainNanos / NANOS_PER_MILLI} placeholderMs=${it.placeholderNanos / NANOS_PER_MILLI}"
                },
        )
        for (r in results) {
            assertTrue(r.different.isEmpty(), "${r.corpus}: classes whose bytes differ with placeholders: ${r.different}")
            assertTrue(
                r.unexplained.isEmpty(),
                "${r.corpus}: classes that wove with placeholders after failing for another reason: ${r.unexplained}",
            )
            assertTrue(r.plainWoven > 0, "${r.corpus}: nothing wove without placeholders, so the comparison covers nothing")
        }
        val webmvc = results.single { it.corpus == "spring-webmvc" }
        assertTrue(
            webmvc.recovered.size >= MIN_RECOVERED_WEBMVC,
            "spring-webmvc: only ${webmvc.recovered.size} classes that named an absent type were recovered: ${webmvc.recovered}",
        )
        assertTrue(
            "jar:org/springframework/web/servlet/view/freemarker/FreeMarkerConfig" in webmvc.recovered,
            "FreeMarkerConfig names freemarker.template.Configuration, absent from the classpath, and failed to weave without placeholders",
        )
    }

    private fun classpathLoader(
        corpusName: String,
        setName: String,
    ): URLClassLoader {
        val classpath =
            checkNotNull(System.getProperty("$CLASSPATH_PROPERTY$corpusName.$setName")) { "no classpath for $corpusName.$setName" }
        val corpusPath = System.getProperty("${BenchmarkCorpus.PROPERTY_PREFIX}$corpusName.$setName").orEmpty()
        val urls =
            (corpusPath.split(File.pathSeparator) + classpath.split(File.pathSeparator))
                .filter { it.isNotEmpty() }
                .map { File(it).toURI().toURL() }
        return URLClassLoader(urls.toTypedArray(), ClassLoader.getPlatformClassLoader())
    }

    /** Weaves [className] from the classpath of [corpusName].[setName] and returns what the transform threw, with the registry. */
    private fun weaveOne(
        corpusName: String,
        setName: String,
        includePackage: String,
        className: String,
    ): Pair<Throwable?, ProbeRegistry> {
        val registry = ProbeRegistry()
        classpathLoader(corpusName, setName).use { loader ->
            val internalName = className.replace('.', '/')
            val bytes =
                checkNotNull(
                    loader.getResourceAsStream("$internalName.class"),
                ) { "$className is not on the classpath" }.use { it.readBytes() }
            val transformer = HotPathWeaver.offlineTransformer(listOf(includePackage), registry)
            val failure = runCatching { transformer.transform(loader, internalName, null, null, bytes) }.exceptionOrNull()
            return failure to registry
        }
    }

    /** The root cause of defining and initialising [name] from [bytes] in a loader over [shared], or null where it runs. */
    private fun outcomeOf(
        shared: ClassLoader,
        name: String,
        bytes: ByteArray,
    ): String? {
        val loader =
            object : ClassLoader(shared) {
                override fun loadClass(
                    requested: String,
                    resolve: Boolean,
                ): Class<*> {
                    if (requested != name) return super.loadClass(requested, resolve)
                    synchronized(getClassLoadingLock(requested)) {
                        return findLoadedClass(requested) ?: defineClass(requested, bytes, 0, bytes.size)
                    }
                }
            }
        return try {
            Class.forName(name, true, loader)
            null
        } catch (t: Throwable) {
            val root = generateSequence(t) { it.cause }.last()
            "${root.javaClass.name}: ${root.message?.replace('/', '.')}"
        }
    }

    /** Weaves [className] and requires it to behave as its unwoven twin. */
    private fun assertWovenAsItsTwin(
        corpusName: String,
        setName: String,
        className: String,
    ) {
        val (failure, registry) = weaveOne(corpusName, setName, "org.springframework", className)
        assertTrue(
            failure == null,
            "$className should weave: ${generateSequence(failure) { it.cause }.joinToString(" <- ") { it.message.orEmpty() }}",
        )
        assertTrue(
            registry
                .manifest(
                    ResourceAttributes("test", null, "instance-1", null, "run-1"),
                ).skippedClasses
                .none { it.className == className },
            "$className is reported as skipped",
        )
        classpathLoader(corpusName, setName).use { loader ->
            val internalName = className.replace('.', '/')
            val original = loader.getResourceAsStream("$internalName.class")!!.use { it.readBytes() }
            val transformer = HotPathWeaver.offlineTransformer(listOf("org.springframework"), ProbeRegistry())
            val woven = checkNotNull(transformer.transform(loader, internalName, null, null, original)) { "$className was not woven" }
            assertEquals(outcomeOf(loader, className, original), outcomeOf(loader, className, woven), "$className woven against unwoven")
        }
    }

    @Test
    fun `a Spring class that types a local Reactor's ContextView is woven and fails as its twin does`() {
        assertWovenAsItsTwin("demo-spring", "main", "org.springframework.core.PropagationContextElement\$ReactorDelegate")
    }

    @Test
    fun `a Spring MVC tag writer that names an absent type in its frames is woven and behaves as its twin`() {
        assertWovenAsItsTwin("spring-webmvc", "jar", "org.springframework.web.servlet.tags.form.TagWriter\$SafeWriter")
    }

    private companion object {
        const val CLASSPATH_PROPERTY = "otherlode.codesize.classpath."
        const val MIN_RECOVERED_WEBMVC = 24
        const val NANOS_PER_MILLI = 1_000_000
        val CORPORA = listOf("demo", "demo-spring", "scala", "spring-webmvc", "ktor-server-core")
    }
}
