package dev.otherlode.instrumentation

import dev.otherlode.config.AgentConfig
import dev.otherlode.export.DeclaredClass
import dev.otherlode.export.OutsideCaller
import dev.otherlode.export.OutsideCallerKind
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ProbeLocation
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.instrumentation.branch.BranchDropReason
import dev.otherlode.instrumentation.branch.BranchKeys
import dev.otherlode.instrumentation.branch.BranchSiteAnalyzer
import dev.otherlode.instrumentation.staticscan.StaticBaselineScanner
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URL
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves through the real pipeline and the static scanner, on entities that Ebean, EclipseLink and
 * OpenJPA enhanced with their own offline enhancers (`fixtures-jpa`), the rules of ADR 0079: an
 * enhancer's methods get no probe and no declaration, a call to one passes through, EclipseLink's
 * woven checks get no outcome claim, and an OpenJPA accessor moved to `pc<name>` reports under the
 * adopter's name.
 */
class JpaEnhancementTest {
    private companion object {
        const val PACKAGE = "com.example.target.jpa"
        val RESOURCE = ResourceAttributes("test", null, "instance-1", null, "run-1")

        fun library(name: String): Library =
            Library(
                File(System.getProperty("otherlode.fixtures.jpa.$name.dir") ?: error("run the tests through the root Gradle build")),
                File(System.getProperty("otherlode.fixtures.jpa.$name.rawDir") ?: error("run the tests through the root Gradle build")),
                (System.getProperty("otherlode.fixtures.jpa.$name.classpath") ?: error("run the tests through the root Gradle build"))
                    .split(File.pathSeparator)
                    .filter { it.isNotEmpty() }
                    .map(::File),
            )
    }

    /** One library's enhanced class directory and the runtime classpath its entities need. */
    private class Library(
        val directory: File,
        val rawDirectory: File,
        val runtime: List<File>,
    ) {
        /**
         * A loader that serves [internalName]'s raw class file as the resource and defines [defined]
         * for it, as a library that enhances at load time does. Its other classes come from the raw
         * directory.
         */
        fun loadTimeLoader(
            internalName: String,
            defined: ByteArray = classBytes(internalName),
            classFileDirectory: File = rawDirectory,
        ): FixtureClassLoader {
            val urls = (listOf(classFileDirectory, rawDirectory) + runtime).map { it.toURI().toURL() }.toTypedArray()
            val path = "$internalName.class"
            val source = java.security.CodeSource(classFileDirectory.toURI().toURL(), null as Array<java.security.cert.Certificate>?)
            return object : FixtureClassLoader(urls, JpaEnhancementTest::class.java.classLoader) {
                override fun findClass(name: String): Class<*> =
                    if (name.replace('.', '/') + ".class" == path) {
                        defineClass(name, defined, 0, defined.size, source)
                    } else {
                        super.findClass(name)
                    }
            }
        }

        fun rawClassBytes(internalName: String): ByteArray = File(rawDirectory, "$internalName.class").readBytes()

        fun loader(
            resourceOverrides: Map<String, ByteArray> = emptyMap(),
            hidden: Set<String> = emptySet(),
        ): FixtureClassLoader {
            val urls = (listOf(directory) + runtime).map { it.toURI().toURL() }.toTypedArray()
            return object : FixtureClassLoader(urls, JpaEnhancementTest::class.java.classLoader) {
                override fun getResourceAsStream(name: String): InputStream? =
                    resourceOverrides[name]?.let { ByteArrayInputStream(it) } ?: super.getResourceAsStream(name)

                override fun getResource(name: String): URL? = if (name in hidden) null else super.getResource(name)
            }
        }

        fun classBytes(internalName: String): ByteArray = File(directory, "$internalName.class").readBytes()

        val lookup: (String) -> ByteArray? = { internalName -> File(directory, "$internalName.class").takeIf { it.isFile }?.readBytes() }
    }

    private var installedTransformer: ResettableClassFileTransformer? = null
    private var installedOtherlode: OtherlodeInstrumentation? = null

    @AfterTest
    fun tearDown() {
        installedTransformer?.let { installedOtherlode?.uninstall(ByteBuddyAgent.install(), it) }
    }

    private fun probesAfterLoading(
        library: Library,
        vararg classNames: String,
        loader: ClassLoader = library.loader(),
        config: String = "includePackages=$PACKAGE",
    ): List<ProbeLocation> {
        val registry = ProbeRegistry()
        val otherlode = OtherlodeInstrumentation(AgentConfig.parse(config), registry)
        installedOtherlode = otherlode
        installedTransformer = otherlode.install(ByteBuddyAgent.install())
        for (name in classNames) Class.forName(name, false, loader)
        return registry.manifest(RESOURCE).probes
    }

    private fun scan(library: Library): Map<String, DeclaredClass> =
        StaticBaselineScanner(listOf(PACKAGE)).scan(listOf(library.directory)).declaredClasses.associateBy { it.className }

    private fun List<ProbeLocation>.methods(className: String): List<ProbeLocation> =
        filter { it.className == className && it.kind == ProbeKind.METHOD }

    private fun List<ProbeLocation>.methodNames(className: String): List<String> =
        methods(className).map { it.methodName }.filter { it != "<clinit>" }

    private fun List<ProbeLocation>.branches(
        className: String,
        methodName: String,
    ): List<ProbeLocation> = filter { it.className == className && it.kind == ProbeKind.BRANCH && it.methodName == methodName }

    private fun ProbeLocation.callees(): List<String> = calls.map { "${it.className}.${it.methodName}" }

    private fun DeclaredClass.methodNames(): List<String> = methods.map { it.methodName }.filter { it != "<clinit>" }

    private val ebean = "$PACKAGE.ebean"
    private val eclipseLink = "$PACKAGE.eclipselink"
    private val openJpa = "$PACKAGE.openjpa"

    @Test
    fun `ebean's members get no probe and no declaration, and the adopter's methods keep theirs`() {
        val library = library("ebean")
        val customer = "$ebean.EbeanCustomer"
        val probes = probesAfterLoading(library, customer)

        val live = probes.methodNames(customer)
        assertEquals(
            listOf("<init>", "getId", "setId", "getName", "setName", "label").sorted(),
            live.sorted(),
            "the adopter's methods and the default constructor are probed, nothing the enhancer added",
        )
        assertEquals(2, probes.branches(customer, "label").size, "the adopter's own branch keeps its outcomes")

        val declared = scan(library).getValue(customer).methodNames()
        assertEquals(live.sorted(), declared.sorted(), "the baseline declares what the live agent probes")
    }

    @Test
    fun `ebean's added toString gets no probe while an adopter's toString keeps one`() {
        val library = library("ebean")
        val customer = "$ebean.EbeanCustomer"
        val named = "$ebean.EbeanNamed"
        val probes = probesAfterLoading(library, customer, named)
        val baseline = scan(library)

        assertTrue(Class.forName(customer, false, library.loader()).declaredMethods.any { it.name == "toString" && it.parameterCount == 0 })
        assertEquals(emptyList(), probes.methodNames(customer).filter { it == "toString" })
        assertEquals(emptyList(), baseline.getValue(customer).methodNames().filter { it == "toString" })

        assertEquals(1, probes.methodNames(named).count { it == "toString" })
        assertEquals(2, probes.branches(named, "toString").size)
        assertEquals(1, baseline.getValue(named).methodNames().count { it == "toString" })
        assertEquals(emptyList(), probes.methodNames(named).filter { it.startsWith("_ebean_") })
    }

    @Test
    fun `a call from other code to an ebean method passes through to what it calls`() {
        val library = library("ebean")
        val caller = "$ebean.EbeanCaller"
        val customer = "$ebean.EbeanCustomer"
        val probes = probesAfterLoading(library, caller, customer)

        val copy = probes.methods(caller).single { it.methodName == "copy" }
        assertTrue("$customer.<init>" in copy.callees(), copy.callees().toString())
        assertEquals(emptyList(), copy.callees().filter { "_ebean_" in it })
        val names = probes.methods(caller).single { it.methodName == "names" }
        assertEquals(emptyList(), names.callees().filter { "_ebean_" in it })

        val getId = probes.methods(customer).single { it.methodName == "getId" }
        assertEquals(emptyList(), getId.callees().filter { "_ebean_" in it })

        val baselineCopy = scan(library).getValue(caller).methods.single { it.methodName == "copy" }
        assertEquals(copy.callees(), baselineCopy.calls.map { "${it.className}.${it.methodName}" })
    }

    @Test
    fun `a call to an ebean member the owner's class file does not declare records no edge`() {
        val library = library("ebean")
        val customer = "$ebean.EbeanCustomer"
        val caller = "$ebean.EbeanCaller"
        val entityPath = customer.replace('.', '/') + ".class"
        val unenhanced = withoutEbean(library.classBytes(customer.replace('.', '/')))
        val loader = library.loader(resourceOverrides = mapOf(entityPath to unenhanced), hidden = setOf(entityPath))

        val probes = probesAfterLoading(library, caller, loader = loader)

        val copy = probes.methods(caller).single { it.methodName == "copy" }
        assertEquals(emptyList(), copy.callees().filter { "_ebean_" in it }, copy.callees().toString())
    }

    @Test
    fun `eclipselink's added methods get no probe, and a hand-written _persistence_ method with a line table keeps its own`() {
        val library = library("eclipselink")
        val customer = "$eclipseLink.ElCustomer"
        val invoice = "$eclipseLink.ElInvoice"
        val probes = probesAfterLoading(library, customer, invoice)
        val baseline = scan(library)

        assertEquals(
            listOf("<init>", "_persistence_custom", "getId", "setId", "getName", "setName", "label").sorted(),
            probes.methodNames(customer).sorted(),
        )
        assertEquals(2, probes.branches(customer, "_persistence_custom").size)
        assertEquals(listOf("<init>", "getId", "setId", "getAccount", "setAccount").sorted(), probes.methodNames(invoice).sorted())
        assertEquals(probes.methodNames(customer).sorted(), baseline.getValue(customer).methodNames().sorted())
        assertEquals(probes.methodNames(invoice).sorted(), baseline.getValue(invoice).methodNames().sorted())
        assertTrue(
            Class.forName(invoice, false, library.loader()).declaredConstructors.any { it.parameterCount == 1 },
            "the copy constructor exists in the class and is not probed",
        )
    }

    @Test
    fun `eclipselink's woven check gets no outcome claim while the adopter's branch in the same method keeps one`() {
        val library = library("eclipselink")
        val invoice = "$eclipseLink.ElInvoice"
        val probes = probesAfterLoading(library, invoice)

        val setId = probes.branches(invoice, "setId")
        assertEquals(4, setId.size, "the adopter's two conditions have two outcomes each, and the weaving's check has none")
        assertTrue(setId.all { it.line > 0 }, setId.map { it.line }.toString())
        assertEquals(emptyList(), probes.branches(invoice, "getAccount"))
        assertEquals(emptyList(), probes.branches(invoice, "setAccount"))
        assertEquals(emptyList(), probes.branches(invoice, "getId"))

        val sites = probes.methods(invoice).single { it.methodName == "setId" }.branchSites
        assertEquals(2, sites.size)
        val baselineSites =
            scan(library)
                .getValue(invoice)
                .methods
                .single { it.methodName == "setId" }
                .branchSites
        assertEquals(sites, baselineSites)
    }

    @Test
    fun `the analyser drops the weaving's checks for JPA_WEAVING and no other site`() {
        val library = library("eclipselink")
        val bytes = library.classBytes("com/example/target/jpa/eclipselink/ElInvoice")

        val analysis = BranchSiteAnalyzer.analyze(bytes) { _, _ -> true }

        val dropped = analysis.sites.filter { it.dropReason == BranchDropReason.JPA_WEAVING }
        assertEquals(listOf("getAccount", "setAccount", "setId"), dropped.map { it.methodName }.sorted())
        assertEquals(2, analysis.sites.count { it.methodName == "setId" && it.dropReason == null }, "the adopter's two sites in setId stay")
        assertEquals(
            emptyList(),
            analysis.sites.filter { it.dropReason == null && it.methodName.startsWith("_persistence_") }.map { it.methodName },
            "an added method is not eligible, so it has no site at all",
        )
    }

    @Test
    fun `a call from other code to an eclipselink method passes through to what it calls`() {
        val library = library("eclipselink")
        val caller = "$eclipseLink.ElCaller"
        val invoice = "$eclipseLink.ElInvoice"
        val probes = probesAfterLoading(library, caller, invoice)

        val holder = probes.methods(caller).single { it.methodName == "holder" }
        assertTrue("$invoice.getAccount" in holder.callees(), holder.callees().toString())
        assertTrue("$invoice.setAccount" in holder.callees(), holder.callees().toString())
        assertEquals(emptyList(), holder.callees().filter { "_persistence_" in it })
        val setId = probes.methods(invoice).single { it.methodName == "setId" }
        assertEquals(emptyList(), setId.callees().filter { "_persistence_" in it })
        assertTrue("$invoice.getId" in setId.callees(), "the weaving's call of the adopter's getter is a real edge")
    }

    @Test
    fun `openjpa's field access entity has no probe on the enhancer's methods and keeps a hand-written pcRate`() {
        val library = library("openjpa")
        val customer = "$openJpa.OjCustomer"
        val probes = probesAfterLoading(library, customer)

        assertEquals(
            listOf("<init>", "getId", "setId", "getName", "setName", "pcRate").sorted(),
            probes.methodNames(customer).sorted(),
        )
        assertEquals(2, probes.branches(customer, "pcRate").size)
        assertEquals(
            probes.methodNames(customer).sorted(),
            scan(library).getValue(customer).methodNames().sorted(),
        )
        val declared = Class.forName(customer, false, library.loader()).declaredMethods.map { it.name }
        assertTrue(declared.containsAll(listOf("pcGetid", "pcSetname", "pcProvideField", "pcIsDetached")), declared.toString())
    }

    @Test
    fun `openjpa's serialization methods on a serializable entity get no probe and no declaration`() {
        val library = library("openjpa")
        val serial = "$openJpa.OjSerial"
        val probes = probesAfterLoading(library, serial)

        assertEquals(listOf("<init>", "getId", "setId").sorted(), probes.methodNames(serial).sorted())
        assertEquals(probes.methodNames(serial).sorted(), scan(library).getValue(serial).methodNames().sorted())
        val declared = Class.forName(serial, false, library.loader()).declaredMethods.map { it.name }
        assertTrue(declared.containsAll(listOf("writeObject", "readObject")), declared.toString())
    }

    @Test
    fun `a moved openjpa accessor keeps the outside caller its wrapper overrides`() {
        val library = library("openjpa")
        val note = "$openJpa.OjNote"
        val probes = probesAfterLoading(library, note)

        assertEquals(
            OutsideCaller(OutsideCallerKind.OVERRIDES_METHOD, "com.example.outside.HasNote"),
            probes.methods(note).single { it.methodName == "getNote" }.outsideCaller,
        )
        assertEquals(null, probes.methods(note).single { it.methodName == "setNote" }.outsideCaller)
    }

    @Test
    fun `a moved openjpa accessor keeps the callback annotations the enhancer moved to its wrapper`() {
        val library = library("openjpa")
        val hook = "$openJpa.OjHook"
        val probes =
            probesAfterLoading(
                library,
                hook,
                config = "includePackages=$PACKAGE,callbackAnnotations=com.example.outside.Hook;com.example.outside.HookClass",
            )
        val outsideCallers = probes.methods(hook).associate { it.methodName to it.outsideCaller }

        assertEquals(OutsideCaller(OutsideCallerKind.CALLBACK_ANNOTATION, "com.example.outside.Hook"), outsideCallers["getNote"])
        assertEquals(OutsideCaller(OutsideCallerKind.CALLBACK_ANNOTATION, "com.example.outside.Hook"), outsideCallers["getTag"])
        assertEquals(OutsideCaller(OutsideCallerKind.CALLBACK_ANNOTATION, "com.example.outside.HookClass"), outsideCallers["getLabel"])
        assertEquals(null, outsideCallers["getPlain"])
    }

    @Test
    fun `openjpa's moved accessors report under the adopter's name and the wrapper has no probe`() {
        val library = library("openjpa")
        val account = "$openJpa.OjAccount"
        val probes = probesAfterLoading(library, account)

        assertEquals(
            listOf("<init>", "getId", "setId", "getOwner", "setOwner").sorted(),
            probes.methodNames(account).sorted(),
            "each adopter method once, under its own name",
        )
        assertEquals(emptyList(), probes.filter { it.className == account && it.methodName.startsWith("pc") })
        val setOwner = probes.branches(account, "setOwner")
        assertEquals(4, setOwner.size, "the adopter's two conditions, and none of the wrapper's")
        assertEquals(emptyList(), probes.branches(account, "getId"))
        assertEquals(emptyList(), probes.branches(account, "getOwner"))
        assertTrue(setOwner.all { it.branchKey != null })
    }

    @Test
    fun `the static baseline and the live agent name an openjpa accessor and its branches alike`() {
        val library = library("openjpa")
        val account = "$openJpa.OjAccount"
        val probes = probesAfterLoading(library, account)
        val baseline = scan(library).getValue(account)

        assertEquals(probes.methodNames(account).sorted(), baseline.methodNames().sorted())
        val live = probes.methods(account).associateBy { it.methodName }
        for (declared in baseline.methods.filter { it.methodName != "<clinit>" }) {
            val probe = live.getValue(declared.methodName)
            assertEquals(probe.methodDescriptor, declared.methodDescriptor, declared.methodName)
            assertEquals(probe.branchSites, declared.branchSites, declared.methodName)
            assertEquals(probe.callees(), declared.calls.map { "${it.className}.${it.methodName}" }, declared.methodName)
        }
        val setOwner = baseline.methods.single { it.methodName == "setOwner" }
        assertEquals(2, setOwner.branchSites.size)
        assertTrue(setOwner.branchSites.all { it.siteKey != null })
    }

    @Test
    fun `a call from other code to openjpa's wrapper and enhancer methods passes through, and the edge names the adopter's method`() {
        val library = library("openjpa")
        val caller = "$openJpa.OjCaller"
        val account = "$openJpa.OjAccount"
        val probes = probesAfterLoading(library, caller, account)

        val rename = probes.methods(caller).single { it.methodName == "rename" }
        assertTrue("$account.setOwner" in rename.callees(), rename.callees().toString())
        assertTrue("$account.getOwner" in rename.callees(), rename.callees().toString())
        assertEquals(emptyList(), rename.callees().filter { ".pc" in it })

        val create = probes.methods(caller).single { it.methodName == "create" }
        assertTrue("$account.<init>" in create.callees(), create.callees().toString())
        assertEquals(emptyList(), create.callees().filter { ".pc" in it })

        val baseline = scan(library).getValue(caller).methods.associateBy { it.methodName }
        assertEquals(rename.callees(), baseline.getValue("rename").calls.map { "${it.className}.${it.methodName}" })
        assertEquals(create.callees(), baseline.getValue("create").calls.map { "${it.className}.${it.methodName}" })
    }

    @Test
    fun `an openjpa accessor's branch keys digest the adopter's name`() {
        val library = library("openjpa")
        val bytes = library.classBytes("com/example/target/jpa/openjpa/OjAccount")
        val className = "$openJpa.OjAccount"

        val analysis = BranchSiteAnalyzer.analyze(bytes, library.lookup) { name, _ -> name != "<clinit>" }

        val reported =
            analysis.keptSites
                .filter { it.site.methodName == "pcsetOwner" }
                .flatMap { it.outcomes }
                .map { it.branchKey }
        val realName = BranchKeys.compute(analysis.sites, className)
        val adopterName = BranchKeys.compute(analysis.sites, className) { name, _ -> if (name == "pcsetOwner") "setOwner" else name }
        val expected =
            analysis.sites.filter { it.methodName == "pcsetOwner" }.flatMap { site ->
                (0 until site.outcomeCount).map {
                    adopterName[
                        site.siteIndex to
                            it,
                    ]
                }
            }
        assertEquals(4, reported.size)
        assertEquals(expected, reported)
        assertTrue(realName.values.none { it in reported }, "the real name would give other keys")
    }

    private class Counted(
        val probes: List<ProbeLocation>,
        private val hits: Map<Pair<Int, Int>, Long>,
    ) {
        fun hits(probe: ProbeLocation): Long = hits[probe.classId to probe.probeIndex] ?: 0
    }

    /**
     * Loads [simpleName] as a load-time enhancer would, runs [exercise] on an instance, and returns
     * the probes and their hit counts. With [definedBytes] given, those bytes are defined instead of
     * the enhanced class.
     */
    private fun loadedAtLoadTime(
        simpleName: String,
        definedBytes: (Library, String) -> ByteArray = { library, internalName -> library.classBytes(internalName) },
        classFileBytes: ((Library, String) -> ByteArray)? = null,
        retransform: Boolean = false,
        exercise: (Class<*>, Any) -> Unit,
    ): Counted {
        val library = library("openjpa")
        val className = "$openJpa.$simpleName"
        val internalName = className.replace('.', '/')
        val registry = ProbeRegistry()
        val otherlode = OtherlodeInstrumentation(AgentConfig.parse("includePackages=$PACKAGE"), registry)
        installedOtherlode = otherlode
        val instrumentation = ByteBuddyAgent.install()
        installedTransformer = otherlode.install(instrumentation)
        val classFileDirectory =
            classFileBytes?.let { bytesOf ->
                kotlin.io.path
                    .createTempDirectory("otherlode-jpa-class-file")
                    .toFile()
                    .also { directory ->
                        directory.deleteOnExit()
                        File(directory, "$internalName.class").apply { parentFile.mkdirs() }.writeBytes(bytesOf(library, internalName))
                    }
            } ?: library.rawDirectory
        val loader = library.loadTimeLoader(internalName, definedBytes(library, internalName), classFileDirectory)
        val type = Class.forName(className, true, loader)
        if (retransform) instrumentation.retransformClasses(type)
        exercise(type, type.getDeclaredConstructor().newInstance())
        val probes = registry.manifest(RESOURCE).probes.filter { it.className == className }
        val hits =
            registry
                .computeDeltaBatch(RESOURCE)
                .batch.deltas
                .associate { (it.classId to it.probeIndex) to it.hitsTotal }
        return Counted(probes, hits)
    }

    private fun Counted.assertWrappersKeepOnlyTheirEntryProbe(
        getter: String,
        setter: String,
        value: Any?,
    ) {
        for (name in listOf(getter, setter)) {
            assertEquals(emptyList(), probes.filter { it.kind == ProbeKind.BRANCH && it.methodName == name }, "$name has no branch probe")
            assertEquals(1, probes.count { it.kind == ProbeKind.METHOD && it.methodName == name }, "$name keeps its entry probe")
        }
        for (name in listOf(getter, setter)) {
            val entry = probes.single { it.kind == ProbeKind.METHOD && it.methodName == name }
            assertEquals(1, hits(entry), "$name was called once ($value)")
        }
    }

    @Test
    fun `an openjpa wrapper enhanced at load time counts its calls on its entry probe`() {
        val counted =
            loadedAtLoadTime("OjNote") { type, instance ->
                type.getMethod("setNote", String::class.java).invoke(instance, "x")
                assertEquals("x", type.getMethod("getNote").invoke(instance))
            }

        counted.assertWrappersKeepOnlyTheirEntryProbe("getNote", "setNote", "x")
    }

    @Test
    fun `an openjpa wrapper enhanced at load time gets no branch probes for the adopter's own branches either`() {
        val counted =
            loadedAtLoadTime("OjLabel") { type, instance ->
                type.getMethod("setLabel", String::class.java).invoke(instance, "x")
                assertEquals("x", type.getMethod("getLabel").invoke(instance))
            }

        counted.assertWrappersKeepOnlyTheirEntryProbe("getLabel", "setLabel", "x")
    }

    @Test
    fun `an openjpa wrapper enhanced at load time gets no branch probes when neither form has line numbers`() {
        val counted =
            loadedAtLoadTime(
                "OjLabel",
                definedBytes = { library, internalName -> withoutDebugInfo(library.classBytes(internalName)) },
                classFileBytes = { library, internalName -> withoutDebugInfo(library.rawClassBytes(internalName)) },
            ) { type, instance ->
                type.getMethod("setLabel", String::class.java).invoke(instance, "x")
                assertEquals("x", type.getMethod("getLabel").invoke(instance))
            }

        counted.assertWrappersKeepOnlyTheirEntryProbe("getLabel", "setLabel", "x")
    }

    @Test
    fun `an openjpa wrapper enhanced at load time stays unpaired after a retransformation`() {
        val counted =
            loadedAtLoadTime("OjLabel", retransform = true) { type, instance ->
                type.getMethod("setLabel", String::class.java).invoke(instance, "x")
                assertEquals("x", type.getMethod("getLabel").invoke(instance))
            }

        counted.assertWrappersKeepOnlyTheirEntryProbe("getLabel", "setLabel", "x")
    }

    @Test
    fun `a class with no enhancer marker in either form keeps its branch pairing when the received bytes differ`() {
        val counted =
            loadedAtLoadTime(
                "OjAccount",
                definedBytes = { library, internalName -> withoutDebugInfo(library.rawClassBytes(internalName)) },
            ) { type, instance ->
                type.getMethod("setOwner", String::class.java).invoke(instance, "x")
            }

        assertEquals(4, counted.probes.count { it.kind == ProbeKind.BRANCH && it.methodName == "setOwner" })
    }

    /** [bytes] without their line-number tables, so they differ from the class file and keep every branch. */
    private fun withoutDebugInfo(bytes: ByteArray): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(bytes).accept(writer, ClassReader.SKIP_DEBUG)
        return writer.toByteArray()
    }

    /** [bytes] without the Ebean marker and every member that starts with `_ebean_`, as a class file on disk predating the enhancer. */
    private fun withoutEbean(bytes: ByteArray): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) = super.visit(
                    version,
                    access,
                    name,
                    signature,
                    superName,
                    interfaces?.filter { it != "io/ebean/bean/EntityBean" }?.toTypedArray(),
                )

                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? =
                    if (name.startsWith("_ebean_")) null else super.visitMethod(access, name, descriptor, signature, exceptions)
            },
            0,
        )
        return writer.toByteArray()
    }
}
