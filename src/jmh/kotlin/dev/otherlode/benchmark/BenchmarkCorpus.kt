package dev.otherlode.benchmark

import dev.otherlode.instrumentation.branch.BranchSiteAnalyzer
import dev.otherlode.instrumentation.endpoints.EndpointModules
import java.io.File
import java.util.zip.ZipFile

class BenchmarkCorpus(
    val name: String,
    val classSets: List<ClassSet>,
) {
    val classCount: Int get() = classSets.sumOf { it.classes.size }

    val includePackages: List<String> =
        classSets
            .flatMap { it.classes.keys }
            .map { it.substringBeforeLast('/', "").replace('/', '.') }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .fold(mutableListOf()) { kept, pkg ->
                if (kept.none { pkg == it || pkg.startsWith("$it.") }) kept += pkg
                kept
            }

    fun analyzeAll(consume: (BranchSiteAnalyzer.Analysis) -> Unit) {
        for (classSet in classSets) {
            val tableCache = BranchSiteAnalyzer.CrossClassTableCache(TABLE_CACHE_ENTRIES)
            val lookup: (String) -> ByteArray? = classSet.classes::get
            for (bytes in classSet.classes.values) {
                consume(
                    BranchSiteAnalyzer.analyze(
                        bytes,
                        lookup,
                        includePackages,
                        emptyList(),
                        tableCache,
                        handlerInterfaces,
                    ) { _, _ -> true },
                )
            }
        }
    }

    companion object {
        const val PROPERTY_PREFIX = "otherlode.benchmark.corpus."

        private const val TABLE_CACHE_ENTRIES = 2048

        private val handlerInterfaces: Set<String> by lazy {
            EndpointModules.discover().flatMapTo(sortedSetOf()) { it.handlerInterfaces }
        }

        fun load(name: String): BenchmarkCorpus {
            val prefix = "$PROPERTY_PREFIX$name."
            val classSets =
                System
                    .getProperties()
                    .stringPropertyNames()
                    .filter { it.startsWith(prefix) }
                    .sorted()
                    .map { key -> ClassSet(key.removePrefix(prefix), readClasses(System.getProperty(key))) }
            check(classSets.isNotEmpty()) { "no system property starts with $prefix; run the benchmark through Gradle" }
            return BenchmarkCorpus(name, classSets)
        }

        private fun readClasses(pathList: String): Map<String, ByteArray> {
            val classes = sortedMapOf<String, ByteArray>()
            for (path in pathList.split(File.pathSeparator).filter { it.isNotEmpty() }) {
                val file = File(path)
                when {
                    file.isDirectory -> {
                        file
                            .walkTopDown()
                            .filter { it.isFile }
                            .forEach { entry ->
                                val relative = entry.relativeTo(file).invariantSeparatorsPath
                                if (isClassEntry(relative)) classes[relative.removeSuffix(".class")] = entry.readBytes()
                            }
                    }

                    file.isFile -> {
                        ZipFile(file).use { zip ->
                            for (entry in zip.entries()) {
                                if (!entry.isDirectory && isClassEntry(entry.name)) {
                                    classes[entry.name.removeSuffix(".class")] = zip.getInputStream(entry).use { it.readBytes() }
                                }
                            }
                        }
                    }

                    else -> {
                        error("corpus path $path does not exist")
                    }
                }
            }
            return classes
        }

        private fun isClassEntry(path: String): Boolean =
            path.endsWith(".class") && !path.startsWith("META-INF/") && !path.endsWith("module-info.class")
    }
}

class ClassSet(
    val name: String,
    val classes: Map<String, ByteArray>,
)
