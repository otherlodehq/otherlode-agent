package dev.otherlode.benchmark

import java.io.File
import java.net.URL

internal object CorpusWeaving {
    const val CLASSPATH_PROPERTY = "otherlode.codesize.classpath."

    val CORPORA = listOf("demo", "demo-spring", "scala", "spring-webmvc", "ktor-server-core")

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
