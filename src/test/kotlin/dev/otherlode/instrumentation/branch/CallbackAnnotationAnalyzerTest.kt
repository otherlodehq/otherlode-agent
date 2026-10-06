package dev.otherlode.instrumentation.branch

import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.utility.OpenedClassReader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Proves which callback annotation [BranchSiteAnalyzer] names for a method, on javac output of the
 * `callbacks` fixtures. The annotation types carry the binary names of the framework ones, since
 * the list matches names.
 */
class CallbackAnnotationAnalyzerTest {
    private val includePackages = listOf("com.example.target.callbacks")

    private val reads = mutableListOf<String>()

    private fun fromBuildOutput(internalName: String): ByteArray? =
        ClassFileLocator
            .ForFolder(File("build/classes/java/test"))
            .locate(internalName.replace('/', '.'))
            .takeIf { it.isResolved }
            ?.resolve()
            ?: javaClass.classLoader.getResourceAsStream("$internalName.class")?.use { it.readBytes() }

    private val lookup: (String) -> ByteArray? = { internalName ->
        reads += internalName
        fromBuildOutput(internalName)
    }

    private fun annotationOf(
        method: String,
        lookup: (String) -> ByteArray? = this.lookup,
        outOfScopeLookup: ((String) -> ByteArray?)? = null,
        tableCache: BranchSiteAnalyzer.CrossClassTableCache? = null,
        descriptor: String = "()V",
    ): String? =
        checkNotNull(fromBuildOutput("com/example/target/callbacks/Listeners")).let { bytes ->
            BranchSiteAnalyzer
                .analyzeThrough(
                    OpenedClassReader.of(bytes),
                    bytes,
                    lookup,
                    includePackages,
                    emptyList(),
                    tableCache,
                    emptySet(),
                    { null },
                    null,
                    outsideCallers = true,
                    outOfScopeLookup = outOfScopeLookup,
                    methodFilter = { _, _ -> true },
                ).callbackAnnotationOf(method, descriptor)
        }

    @Test
    fun `a listed annotation on the method names itself`() {
        assertEquals("org.springframework.context.event.EventListener", annotationOf("onEvent"))
        assertEquals("org.springframework.web.bind.annotation.GetMapping", annotationOf("get", descriptor = "()Ljava/lang/String;"))
    }

    @Test
    fun `a composed annotation is named as written, at one level and at two`() {
        assertEquals("com.example.target.callbacks.Composed", annotationOf("composed"))
        assertEquals("com.example.target.callbacks.DeepComposed", annotationOf("deep"))
    }

    @Test
    fun `a parameter Observes marks the method, and a ModelAttribute counts only on the method`() {
        assertEquals("jakarta.enterprise.event.Observes", annotationOf("observe", descriptor = "(Ljava/lang/String;Ljava/lang/String;)V"))
        assertNull(annotationOf("modelOnParameter", descriptor = "(Ljava/lang/String;)V"))
        assertEquals("org.springframework.web.bind.annotation.ModelAttribute", annotationOf("modelOnMethod"))
        assertNull(annotationOf("observesOnMethod"))
    }

    @Test
    fun `an unlisted annotation, a class-retention listed name and a plain method are not marked`() {
        assertNull(annotationOf("unlisted"))
        assertNull(annotationOf("classRetained"))
        assertNull(annotationOf("plain"))
        assertNull(annotationOf("beside", descriptor = "(Ljava/lang/String;)V"))
    }

    @Test
    fun `a listed annotation wins over an override, and the first in class-file order wins between two`() {
        assertEquals("org.springframework.scheduling.annotation.Scheduled", annotationOf("run"))
        assertEquals("org.springframework.web.bind.annotation.GetMapping", annotationOf("getFirst"))
        assertEquals("org.springframework.context.event.EventListener", annotationOf("eventFirst"))
    }

    @Test
    fun `a constructor with Inject is not marked, and a setter with Inject is`() {
        assertNull(annotationOf("<init>"))
        assertEquals("jakarta.inject.Inject", annotationOf("setDependency", descriptor = "(Ljava/lang/String;)V"))
    }

    @Test
    fun `a static Bean method and a private EventListener method are marked`() {
        assertEquals("org.springframework.context.annotation.Bean", annotationOf("bean", descriptor = "()Ljava/lang/Object;"))
        assertEquals("org.springframework.context.event.EventListener", annotationOf("hidden"))
    }

    @Test
    fun `an annotation type that cannot be read and is not listed marks nothing and throws nothing`() {
        val hiding: (String) -> ByteArray? = { name -> if (name.endsWith("/Vanished")) null else lookup(name) }

        assertNull(annotationOf("vanished", lookup = hiding))
        assertEquals("com.example.target.callbacks.Vanished", annotationOf("vanished"))
    }

    @Test
    fun `an annotation that carries itself ends the walk and marks nothing`() {
        assertNull(annotationOf("looped"))
    }

    @Test
    fun `out-of-scope annotation types are read through the out-of-scope lookup and not the shared one`() {
        val outOfScopeReads = mutableListOf<String>()
        val outOfScope: (String) -> ByteArray? = { name ->
            outOfScopeReads += name
            fromBuildOutput(name)
        }
        reads.clear()

        annotationOf("unlisted", outOfScopeLookup = outOfScope)

        assertEquals(true, "java/lang/Deprecated" in outOfScopeReads)
        assertEquals(false, "java/lang/Deprecated" in reads)
        assertEquals(true, "com/example/target/callbacks/Marker" in reads)
    }

    @Test
    fun `an annotation inside a cycle is answered by its own reachability, not by an answer cut short inside another walk`() {
        val cache = BranchSiteAnalyzer.CrossClassTableCache(100)

        assertEquals("com.example.target.callbacks.CycleX", annotationOf("cycleX", tableCache = cache))
        assertEquals("com.example.target.callbacks.CycleY", annotationOf("cycleY", tableCache = cache))
    }

    @Test
    fun `an annotation on the method wins over one on a parameter`() {
        assertEquals(
            "org.springframework.web.bind.annotation.GetMapping",
            annotationOf("mappedObserver", descriptor = "(Ljava/lang/String;)V"),
        )
    }

    @Test
    fun `the walk never reads an annotation type from java lang annotation`() {
        reads.clear()

        annotationOf("composed")

        assertEquals(emptyList(), reads.filter { it.startsWith("java/lang/annotation/") })
    }

    @Test
    fun `a shared cache answers a second analysis without reading the annotation types again`() {
        val cache = BranchSiteAnalyzer.CrossClassTableCache(100)
        annotationOf("composed", tableCache = cache)
        reads.clear()

        annotationOf("composed", tableCache = cache)

        assertEquals(emptyList(), reads.filter { it.endsWith("/Composed") || it.endsWith("/Marker") })
    }
}
