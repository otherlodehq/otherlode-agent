package dev.otherlode.benchmark

import java.io.File
import java.net.URL

/** What the tests that weave the benchmark corpora share: the corpus names and where each class set resolves its types. */
internal object CorpusWeaving {
    /** The system property prefix under which the build passes each class set's dependency classpath. */
    const val CLASSPATH_PROPERTY = "otherlode.codesize.classpath."

    /** The benchmark corpora, by name. */
    val CORPORA = listOf("demo", "demo-spring", "scala", "spring-webmvc", "ktor-server-core")

    /**
     * The URLs a class loader needs to resolve the types of [set] in [corpusName]: the class set's own
     * classes, then its dependency classpath. Fails when the build passed no classpath for the set.
     */
    fun classSetUrls(
        corpusName: String,
        set: ClassSet,
    ): List<URL> {
        val classpath = System.getProperty("$CLASSPATH_PROPERTY$corpusName.${set.name}")
        check(!classpath.isNullOrBlank()) { "no classpath for $corpusName.${set.name}" }
        val corpusPath = checkNotNull(System.getProperty("${BenchmarkCorpus.PROPERTY_PREFIX}$corpusName.${set.name}"))
        return (corpusPath.split(File.pathSeparator) + classpath.split(File.pathSeparator))
            .filter { it.isNotEmpty() }
            .map { File(it).toURI().toURL() }
    }
}
