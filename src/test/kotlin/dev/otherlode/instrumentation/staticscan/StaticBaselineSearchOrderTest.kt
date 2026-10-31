package dev.otherlode.instrumentation.staticscan

import dev.otherlode.export.CallEdge
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Opcodes
import java.io.File
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.logging.Level as JulLevel
import java.util.logging.Logger as JulLogger

/** Which copy of a class name the static baseline keeps, in the order the JVM's class loaders search. */
class StaticBaselineSearchOrderTest {
    private val dupName = "com/example/target/Dup"
    private val dupClassName = "com.example.target.Dup"
    private val scanner = StaticBaselineScanner(listOf("com.example.target"))

    private fun classBytes(path: String): ByteArray = File("build/classes/$path").readBytes()

    private fun dupBytes(methodName: String): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, dupName, null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()V", null, null)
        mv.visitCode()
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun directoryRoot(vararg entries: Pair<String, ByteArray>): File {
        val root =
            kotlin.io.path
                .createTempDirectory("otherlode-search-order")
                .toFile()
        for ((relativePath, bytes) in entries) {
            val file = File(root, relativePath)
            file.parentFile.mkdirs()
            file.writeBytes(bytes)
        }
        return root
    }

    /** Writes [entries] into [jar] after a `META-INF/MANIFEST.MF` holding [manifest] verbatim, or none when it is null. */
    private fun writeJar(
        jar: File,
        manifest: String?,
        vararg entries: Pair<String, ByteArray>,
    ): File {
        jar.parentFile.mkdirs()
        ZipOutputStream(jar.outputStream()).use { out ->
            if (manifest != null) {
                out.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
                out.write(manifest.toByteArray())
                out.closeEntry()
            }
            for ((name, bytes) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return jar
    }

    private fun manifestOf(vararg attributes: String): String = (listOf("Manifest-Version: 1.0") + attributes).joinToString("\n", postfix = "\n\n")

    private fun methodsOfDup(result: StaticScanResult): List<String> =
        result.declaredClasses
            .single { it.className == dupClassName }
            .methods
            .map { it.methodName }

    private fun captureWarnings(block: () -> Unit): List<LogRecord> {
        val records = mutableListOf<LogRecord>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    records += record
                }

                override fun flush() {}

                override fun close() {}
            }
        val julLogger = JulLogger.getLogger(StaticBaselineScanner::class.java.name)
        val originalLevel = julLogger.level
        julLogger.addHandler(handler)
        julLogger.level = JulLevel.ALL
        try {
            block()
        } finally {
            julLogger.removeHandler(handler)
            julLogger.level = originalLevel
        }
        return records.filter { it.level == JulLevel.WARNING }
    }

    private fun scan(vararg roots: File): StaticScanResult = scanner.scan(roots.toList())

    private val nestedPrefixes = listOf("BOOT-INF/classes/", "WEB-INF/classes/")

    @Test
    fun `a flat directory root after a nested classes root wins over it`() {
        for (prefix in nestedPrefixes) {
            val nested = writeJar(File(directoryRoot(), "app.jar"), manifestOf(), "$prefix$dupName.class" to dupBytes("fromNested"))
            val flat = directoryRoot("$dupName.class" to dupBytes("fromFlat"))

            val result = scan(nested, flat)

            assertEquals(listOf("fromFlat"), methodsOfDup(result), prefix)
            assertEquals(setOf(dupClassName), result.ownClassNames, prefix)
        }
    }

    @Test
    fun `a flat jar root after a nested classes root wins over it`() {
        for (prefix in nestedPrefixes) {
            val nested = writeJar(File(directoryRoot(), "app.jar"), manifestOf(), "$prefix$dupName.class" to dupBytes("fromNested"))
            val flat = writeJar(File(directoryRoot(), "flat.jar"), manifestOf(), "$dupName.class" to dupBytes("fromFlat"))

            val result = scan(nested, flat)

            assertEquals(listOf("fromFlat"), methodsOfDup(result), prefix)
            assertEquals(setOf(dupClassName), result.flatJarClassNames, prefix)
            assertTrue(result.ownClassNames.isEmpty(), prefix)
        }
    }

    @Test
    fun `a nested classes copy still wins over a copy in no flat root, and the first nested root wins among nested ones`() {
        val first = writeJar(File(directoryRoot(), "first.jar"), manifestOf(), "BOOT-INF/classes/$dupName.class" to dupBytes("fromFirst"))
        val second = writeJar(File(directoryRoot(), "second.jar"), manifestOf(), "BOOT-INF/classes/$dupName.class" to dupBytes("fromSecond"))

        val result = scan(first, second)

        assertEquals(listOf("fromFirst"), methodsOfDup(result))
    }

    @Test
    fun `a jar's own root-level copy of a name wins over its nested classes copy, whichever entry comes first`() {
        val nested = "BOOT-INF/classes/$dupName.class" to dupBytes("fromNested")
        val atRoot = "$dupName.class" to dupBytes("fromRoot")
        for (entries in listOf(arrayOf(nested, atRoot), arrayOf(atRoot, nested))) {
            val jar = writeJar(File(directoryRoot(), "app.jar"), manifestOf(), *entries)

            assertEquals(listOf("fromRoot"), methodsOfDup(scan(jar)), entries.first().first)
        }
    }

    @Test
    fun `a class held only under META-INF versions of an earlier multi-release jar takes its name`() {
        val earlier =
            writeJar(
                File(directoryRoot(), "mr.jar"),
                manifestOf("Multi-Release: true"),
                "META-INF/versions/9/$dupName.class" to dupBytes("fromVersioned"),
            )
        val later = directoryRoot("$dupName.class" to dupBytes("fromLater"))

        val result = scan(earlier, later)

        assertTrue(result.declaredClasses.isEmpty(), "the later copy is not kept: ${result.declaredClasses.map { it.className }}")
        assertTrue(result.unprobedClasses.isEmpty())
        assertTrue(result.unreadableClasses.isEmpty())
        assertTrue(result.ownClassNames.isEmpty())
        assertTrue(result.flatJarClassNames.isEmpty())
    }

    @Test
    fun `a Multi-Release attribute is read as the JDK reads it, ignoring case`() {
        val earlier =
            writeJar(
                File(directoryRoot(), "mr.jar"),
                manifestOf("Multi-Release: TRUE"),
                "META-INF/versions/9/$dupName.class" to dupBytes("fromVersioned"),
            )
        val later = directoryRoot("$dupName.class" to dupBytes("fromLater"))

        assertTrue(scan(earlier, later).declaredClasses.isEmpty())
    }

    @Test
    fun `a versioned class takes its name at the runtime's own version and below, not above it`() {
        val feature = Runtime.version().feature()
        for ((version, taken) in listOf(feature to true, 8 to true, feature + 1 to false, 7 to false)) {
            val earlier =
                writeJar(
                    File(directoryRoot(), "mr.jar"),
                    manifestOf("Multi-Release: true"),
                    "META-INF/versions/$version/$dupName.class" to dupBytes("fromVersioned"),
                )
            val later = directoryRoot("$dupName.class" to dupBytes("fromLater"))

            val result = scan(earlier, later)

            if (taken) {
                assertTrue(result.declaredClasses.isEmpty(), "version $version")
            } else {
                assertEquals(listOf("fromLater"), methodsOfDup(result), "version $version")
            }
        }
    }

    @Test
    fun `a versioned folder with a leading zero is not read, as the JDK looks only for the plain number`() {
        val earlier =
            writeJar(
                File(directoryRoot(), "mr.jar"),
                manifestOf("Multi-Release: true"),
                "META-INF/versions/09/$dupName.class" to dupBytes("fromVersioned"),
            )
        val later = directoryRoot("$dupName.class" to dupBytes("fromLater"))

        assertEquals(listOf("fromLater"), methodsOfDup(scan(earlier, later)))
    }

    @Test
    fun `a versioned class in a jar that is not multi-release takes no name`() {
        for (manifest in listOf(manifestOf(), manifestOf("Multi-Release: false"), null)) {
            val earlier = writeJar(File(directoryRoot(), "plain.jar"), manifest, "META-INF/versions/9/$dupName.class" to dupBytes("fromVersioned"))
            val later = directoryRoot("$dupName.class" to dupBytes("fromLater"))

            val result = scan(earlier, later)

            assertEquals(listOf("fromLater"), methodsOfDup(result), "manifest $manifest")
        }
    }

    @Test
    fun `a versioned copy beside a base copy leaves the base copy as the kept one`() {
        val earlier =
            writeJar(
                File(directoryRoot(), "mr.jar"),
                manifestOf("Multi-Release: true"),
                "META-INF/versions/9/$dupName.class" to dupBytes("fromVersioned"),
                "$dupName.class" to dupBytes("fromBase"),
            )
        val later = directoryRoot("$dupName.class" to dupBytes("fromLater"))

        val result = scan(earlier, later)

        assertEquals(listOf("fromBase"), methodsOfDup(result))
    }

    @Test
    fun `a versioned class under BOOT-INF classes takes no name, as the launcher reads that folder as plain classes`() {
        val earlier =
            writeJar(
                File(directoryRoot(), "app.jar"),
                manifestOf("Multi-Release: true"),
                "BOOT-INF/classes/META-INF/versions/9/$dupName.class" to dupBytes("fromVersioned"),
            )
        val later = directoryRoot("$dupName.class" to dupBytes("fromLater"))

        assertEquals(listOf("fromLater"), methodsOfDup(scan(earlier, later)))
    }

    @Test
    fun `a jar whose manifest cannot be read is dropped with a WARNING, and the Class-Path in it is not followed`() {
        val dir = directoryRoot()
        val unreadable =
            writeJar(
                File(dir, "bad.jar"),
                "Manifest-Version: 1.0\nClass-Path: next.jar\nnot a header\n\n",
                "$dupName.class" to dupBytes("fromBad"),
            )
        writeJar(File(dir, "next.jar"), manifestOf(), "com/example/target/Next.class" to dupBytes("fromNext"))
        val later = directoryRoot("$dupName.class" to dupBytes("fromLater"))

        lateinit var result: StaticScanResult
        val warnings = captureWarnings { result = scan(unreadable, later) }

        assertEquals(setOf(dupClassName), result.allClassNames())
        assertEquals(listOf("fromLater"), methodsOfDup(result))
        assertEquals(1, warnings.size, warnings.joinToString { it.message })
        assertTrue(warnings.single().message.contains("bad.jar"), warnings.single().message)
    }

    @Test
    fun `a jar whose manifest cannot be read is dropped even when it names no Class-Path`() {
        val unreadable =
            writeJar(
                File(directoryRoot(), "bad.jar"),
                "Manifest-Version: 1.0\nnot a header\n\n",
                "$dupName.class" to dupBytes("fromBad"),
            )
        val later = directoryRoot("$dupName.class" to dupBytes("fromLater"))

        lateinit var result: StaticScanResult
        val warnings = captureWarnings { result = scan(unreadable, later) }

        assertEquals(listOf("fromLater"), methodsOfDup(result))
        assertEquals(1, warnings.size, warnings.joinToString { it.message })
        assertTrue(warnings.single().message.contains("bad.jar"), warnings.single().message)
    }

    @Test
    fun `a jar with no manifest at all is scanned`() {
        val jar = writeJar(File(directoryRoot(), "bare.jar"), null, "$dupName.class" to dupBytes("fromBare"))

        assertEquals(listOf("fromBare"), methodsOfDup(scan(jar)))
    }

    /** Scans a jar whose `Class-Path` is [classPath], beside [jars]: each a path under its directory and the one method its class holds. */
    private fun classPathChain(
        classPath: String,
        vararg jars: Pair<String, String>,
    ): StaticScanResult {
        val dir = directoryRoot()
        val app = writeJar(File(dir, "app.jar"), manifestOf("Class-Path: $classPath"))
        for ((path, method) in jars) {
            val simple = method.replaceFirstChar { it.uppercase() }
            writeJar(File(dir, path), manifestOf(), "com/example/target/$simple.class" to classNamedAfter(method))
        }
        return scan(app)
    }

    /** A class named after [method] with that one concrete method, so the class name says which jar it came from. */
    private fun classNamedAfter(method: String): ByteArray {
        val simple = method.replaceFirstChar { it.uppercase() }
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/target/$simple", null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, method, "()V", null, null)
        mv.visitCode()
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun declaredNames(result: StaticScanResult): List<String> = result.declaredClasses.map { it.className.substringAfterLast('.') }

    @Test
    fun `Class-Path entries are split on runs of spaces`() {
        val result = classPathChain("b.jar     d.jar", "b.jar" to "bee", "d.jar" to "dee")

        assertEquals(listOf("Bee", "Dee"), declaredNames(result))
    }

    @Test
    fun `Class-Path entries are split on tabs too`() {
        val result = classPathChain("b.jar\t\td.jar", "b.jar" to "bee", "d.jar" to "dee")

        assertEquals(listOf("Bee", "Dee"), declaredNames(result))
    }

    @Test
    fun `a percent escape in a Class-Path entry is decoded`() {
        val result = classPathChain("lib%20dir/b.jar", "lib dir/b.jar" to "bee")

        assertEquals(listOf("Bee"), declaredNames(result))
    }

    @Test
    fun `a raw space splits a Class-Path entry in two, so a path with a space in it is not found`() {
        val result = classPathChain("lib dir/b.jar", "lib dir/b.jar" to "bee")

        assertTrue(result.declaredClasses.isEmpty())
    }

    @Test
    fun `a file URL in a Class-Path names its file`() {
        val dir = directoryRoot()
        val target = writeJar(File(dir, "elsewhere/b.jar"), manifestOf(), "com/example/target/Bee.class" to classNamedAfter("bee"))
        val app = writeJar(File(dir, "app.jar"), manifestOf("Class-Path: ${target.toURI()}"))

        assertEquals(listOf("Bee"), declaredNames(scan(app)))
    }

    @Test
    fun `a Class-Path entry ending in a slash is read as a directory of classes`() {
        val dir = directoryRoot()
        File(dir, "classes/com/example/target").mkdirs()
        File(dir, "classes/com/example/target/Bee.class").writeBytes(classNamedAfter("bee"))
        val app = writeJar(File(dir, "app.jar"), manifestOf("Class-Path: classes/"))

        val result = scan(app)

        assertEquals(listOf("Bee"), declaredNames(result))
        assertEquals(setOf("com.example.target.Bee"), result.ownClassNames)
    }

    @Test
    fun `a Class-Path entry naming a directory without a trailing slash is not read, as the JDK reads it only as a jar`() {
        val dir = directoryRoot()
        File(dir, "classes/com/example/target").mkdirs()
        File(dir, "classes/com/example/target/Bee.class").writeBytes(classNamedAfter("bee"))
        val app = writeJar(File(dir, "app.jar"), manifestOf("Class-Path: classes"))

        assertTrue(scan(app).declaredClasses.isEmpty())
    }

    @Test
    fun `a Class-Path entry naming a jar with a trailing slash is not read, as the JDK reads it only as a directory`() {
        val dir = directoryRoot()
        writeJar(File(dir, "b.jar"), manifestOf(), "com/example/target/Bee.class" to classNamedAfter("bee"))
        val app = writeJar(File(dir, "app.jar"), manifestOf("Class-Path: b.jar/"))

        assertTrue(scan(app).declaredClasses.isEmpty())
    }

    @Test
    fun `a Class-Path jar whose manifest cannot be read is dropped with the jars it names`() {
        val dir = directoryRoot()
        val app = writeJar(File(dir, "app.jar"), manifestOf("Class-Path: bad.jar"))
        writeJar(File(dir, "bad.jar"), "Manifest-Version: 1.0\nClass-Path: c.jar\nnot a header\n\n", "$dupName.class" to dupBytes("fromBad"))
        writeJar(File(dir, "c.jar"), manifestOf(), "com/example/target/Cee.class" to classNamedAfter("cee"))

        lateinit var result: StaticScanResult
        val warnings = captureWarnings { result = scan(app) }

        assertTrue(result.allClassNames().isEmpty(), "${result.allClassNames()}")
        assertEquals(1, warnings.size, warnings.joinToString { it.message })
        assertTrue(warnings.single().message.contains("bad.jar"), warnings.single().message)
    }

    @Test
    fun `a cross-class read uses the copy of the class the JVM loads, not the referencing root's own copy`() {
        val real = classBytes("kotlin/test/com/example/target/DefaultArgumentTarget.class")
        val callerBytes = classBytes("kotlin/test/com/example/target/CallEdgeTarget.class")
        val hollow = hollowClass("com/example/target/DefaultArgumentTarget")
        val first = directoryRoot("com/example/target/DefaultArgumentTarget.class" to real)
        val second =
            directoryRoot(
                "com/example/target/CallEdgeTarget.class" to callerBytes,
                "com/example/target/DefaultArgumentTarget.class" to hollow,
            )

        val result = StaticBaselineScanner(listOf("com.example.target")).scan(listOf(first, second))

        assertEquals(resolvedDefaultEdge, callsWithDefaultArgument(result))
    }

    @Test
    fun `a cross-class read from a nested classes root finds a flat root's copy before its own jar's nested copy`() {
        val real = classBytes("kotlin/test/com/example/target/DefaultArgumentTarget.class")
        val callerBytes = classBytes("kotlin/test/com/example/target/CallEdgeTarget.class")
        val hollow = hollowClass("com/example/target/DefaultArgumentTarget")
        val boot =
            writeJar(
                File(directoryRoot(), "app.jar"),
                manifestOf(),
                "BOOT-INF/classes/com/example/target/CallEdgeTarget.class" to callerBytes,
                "BOOT-INF/classes/com/example/target/DefaultArgumentTarget.class" to hollow,
            )
        val flat = directoryRoot("com/example/target/DefaultArgumentTarget.class" to real)

        val result = StaticBaselineScanner(listOf("com.example.target")).scan(listOf(boot, flat))

        assertEquals(resolvedDefaultEdge, callsWithDefaultArgument(result))
    }

    @Test
    fun `a cross-class read of a class a multi-release jar holds only under META-INF versions goes to the supporting types`() {
        val callerBytes = classBytes("kotlin/test/com/example/target/CallEdgeTarget.class")
        val hollow = hollowClass("com/example/target/DefaultArgumentTarget")
        val versioned =
            writeJar(
                File(directoryRoot(), "mr.jar"),
                manifestOf("Multi-Release: true"),
                "META-INF/versions/9/com/example/target/DefaultArgumentTarget.class" to hollow,
            )
        val caller =
            directoryRoot(
                "com/example/target/CallEdgeTarget.class" to callerBytes,
                "com/example/target/DefaultArgumentTarget.class" to hollow,
            )

        val result = StaticBaselineScanner(listOf("com.example.target")).scan(listOf(versioned, caller))

        assertEquals(resolvedDefaultEdge, callsWithDefaultArgument(result))
    }

    private val resolvedDefaultEdge =
        listOf(CallEdge("com.example.target.DefaultArgumentTarget", "f", "(IILjava/lang/String;J)I", virtual = false))

    private fun callsWithDefaultArgument(result: StaticScanResult) =
        result.declaredClasses
            .single { it.className == "com.example.target.CallEdgeTarget" }
            .methods
            .single { it.methodName == "callsWithDefaultArgument" && it.methodDescriptor == "(Lcom/example/target/DefaultArgumentTarget;)I" }
            .calls

    /** A public class [internalName] with no members, so a `$default` pass-through into it cannot resolve. */
    private fun hollowClass(internalName: String): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        cw.visitEnd()
        return cw.toByteArray()
    }
}
