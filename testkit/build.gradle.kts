import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.api.tasks.bundling.Jar
import java.io.DataInputStream
import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.2.21"
    `jvm-test-suite`
    id("com.gradleup.shadow") version "8.3.11"
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.2"
    `maven-publish`
    signing
    id("com.gradleup.nmcp")
    id("org.jetbrains.dokka") version "2.2.0"
}

group = "dev.otherlode"

repositories {
    mavenCentral()
}

dependencies {
    // The agent itself reaches a test JVM only as its shaded -javaagent jar, never as classes on the
    // test classpath.
    implementation(project(":wire"))

    // compileOnly, so an adopter who does not use JUnit pays nothing. Pinned to the version on this
    // module's test classpath, so main and test code compile against the same API.
    compileOnly("org.junit.jupiter:junit-jupiter-api:5.10.1")

    testImplementation(kotlin("test"))

    testImplementation(project(":"))

    testImplementation("net.bytebuddy:byte-buddy-agent:1.18.12")
    testImplementation("net.bytebuddy:byte-buddy:1.18.12")

    testImplementation(project(":endpoints-jdk-httpserver"))

    testImplementation(project(":endpoints-api"))
}

kotlin {
    jvmToolchain(21)
    explicitApi()
    coreLibrariesVersion = "2.0.21"
}

// Only the main source set is pinned: the test fixtures keep the default language version, whose
// compiler shapes the end-to-end tests read.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

// The plain jar's classifier keeps it from overwriting the shadow jar. The testkit reads the version
// back from its package to refuse an agent of another version.
tasks.jar {
    archiveBaseName.set("otherlode-testkit")
    archiveClassifier.set("plain")
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}

val bundledLicenseFiles =
    mapOf(
        "META-INF/LICENSE" to rootProject.layout.projectDirectory.file("LICENSE"),
        "META-INF/NOTICE" to layout.projectDirectory.file("NOTICE"),
        "META-INF/licenses/protobuf-java/LICENSE" to rootProject.layout.projectDirectory.file("licenses/protobuf-java/LICENSE"),
    )

tasks.shadowJar {
    archiveBaseName.set("otherlode-testkit")
    archiveClassifier.set("")

    dependencies {
        exclude(dependency("org.jetbrains.kotlin:kotlin-stdlib"))
        exclude(dependency("org.jetbrains:annotations"))
    }

    relocate("com.google.protobuf", "dev.otherlode.testkit.shaded.protobuf")
    relocate("dev.otherlode.export", "dev.otherlode.testkit.shaded.export")
    relocate("dev.otherlode.proto", "dev.otherlode.testkit.shaded.proto")
    relocate("dev.otherlode.registry", "dev.otherlode.testkit.shaded.registry")

    // The schema files protobuf-java and the wire module ship as resources would otherwise sit
    // unrelocated on an adopter's classpath; the generated classes carry their own descriptors.
    exclude("google/protobuf/**", "otherlode/**")

    exclude("META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/licenses/**")
    for ((entry, source) in bundledLicenseFiles) {
        from(source) {
            into(entry.substringBeforeLast('/'))
            rename { entry.substringAfterLast('/') }
        }
    }

    manifest {
        attributes("Implementation-Version" to project.version)
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

fun kotlinMetadataVersion(bytes: ByteArray): List<Int>? {
    val input = DataInputStream(bytes.inputStream())
    input.skipBytes(8)
    val pool = arrayOfNulls<Any>(input.readUnsignedShort())
    var index = 1
    while (index < pool.size) {
        when (val tag = input.readUnsignedByte()) {
            1 -> pool[index] = input.readUTF()
            3 -> pool[index] = input.readInt()
            4 -> input.skipBytes(4)
            5, 6 -> {
                input.skipBytes(8)
                index++
            }
            7, 8, 16, 19, 20 -> input.skipBytes(2)
            9, 10, 11, 12, 17, 18 -> input.skipBytes(4)
            15 -> input.skipBytes(3)
            else -> error("unknown constant pool tag $tag")
        }
        index++
    }
    input.skipBytes(6)
    input.skipBytes(2 * input.readUnsignedShort())
    repeat(2) {
        repeat(input.readUnsignedShort()) {
            input.skipBytes(6)
            repeat(input.readUnsignedShort()) {
                input.skipBytes(2)
                input.skipBytes(input.readInt())
            }
        }
    }
    var version: List<Int>? = null
    repeat(input.readUnsignedShort()) {
        val name = pool[input.readUnsignedShort()]
        val length = input.readInt()
        if (name != "RuntimeVisibleAnnotations") {
            input.skipBytes(length)
        } else {
            repeat(input.readUnsignedShort()) {
                val type = pool[input.readUnsignedShort()]
                val pairs = readAnnotationPairs(input, pool)
                if (type == "Lkotlin/Metadata;") version = (pairs["mv"] as? List<*>)?.filterIsInstance<Int>()
            }
        }
    }
    return version
}

fun readAnnotationPairs(
    input: DataInputStream,
    pool: Array<Any?>,
): Map<String, Any?> =
    buildMap {
        repeat(input.readUnsignedShort()) { put(pool[input.readUnsignedShort()] as String, readElementValue(input, pool)) }
    }

fun readElementValue(
    input: DataInputStream,
    pool: Array<Any?>,
): Any? =
    when (val tag = input.readUnsignedByte().toChar()) {
        'e' -> {
            input.skipBytes(4)
            null
        }
        '@' -> {
            input.skipBytes(2)
            readAnnotationPairs(input, pool)
        }
        '[' -> List(input.readUnsignedShort()) { readElementValue(input, pool) }
        else -> pool[input.readUnsignedShort()].takeIf { tag == 'I' }
    }

val verifyTestkitJar by tasks.registering {
    dependsOn(tasks.shadowJar)
    val jarFile = tasks.shadowJar.flatMap { it.archiveFile }
    inputs.file(jarFile)
    inputs.property("licenseEntries", bundledLicenseFiles.keys.sorted())
    doLast {
        ZipFile(jarFile.get().asFile).use { zip ->
            val names =
                zip
                    .entries()
                    .asSequence()
                    .map { it.name }
                    .toList()
            val classes = names.filter { it.endsWith(".class") }
            // The testkit loads in an adopter's test JVM, which may be a JDK 17 (class-file version 61).
            val tooNew =
                classes.filter { name ->
                    val header = zip.getInputStream(zip.getEntry(name)).use { it.readNBytes(8) }
                    ((header[6].toInt() and 0xFF) shl 8 or (header[7].toInt() and 0xFF)) > 61
                }
            check(tooNew.isEmpty()) { "testkit jar has classes above class-file version 61: ${tooNew.take(10)}" }
            val tooNewMetadata =
                classes.filter { name ->
                    val version = kotlinMetadataVersion(zip.getInputStream(zip.getEntry(name)).use { it.readBytes() })
                    version != null && (version[0] > 2 || version[0] == 2 && version[1] > 0)
                }
            check(tooNewMetadata.isEmpty()) {
                "testkit jar has classes with Kotlin metadata above 2.0, which Kotlin 1.9 cannot read: ${tooNewMetadata.take(10)}"
            }
            val foreign = classes.filterNot { it.startsWith("dev/otherlode/testkit/") }
            check(foreign.isEmpty()) { "testkit jar carries classes outside dev/otherlode/testkit/: ${foreign.take(10)}" }
            val unrelocated =
                names.filter {
                    it.startsWith("com/google/protobuf/") ||
                        it.startsWith("dev/otherlode/export/") ||
                        it.startsWith("dev/otherlode/proto/") ||
                        it.startsWith("dev/otherlode/registry/")
                }
            check(unrelocated.isEmpty()) { "testkit jar carries unrelocated entries: ${unrelocated.take(10)}" }
            val schemaFiles = names.filter { it.endsWith(".proto") }
            check(schemaFiles.isEmpty()) { "testkit jar carries unrelocated schema files: $schemaFiles" }
            val kotlinClasses = names.filter { it.startsWith("kotlin/") }
            check(kotlinClasses.isEmpty()) { "testkit jar must not bundle kotlin-stdlib, found: ${kotlinClasses.take(10)}" }
            check(classes.any { it.startsWith("dev/otherlode/testkit/shaded/protobuf/") }) {
                "testkit jar is missing the relocated protobuf-java"
            }
            check(classes.any { it.startsWith("dev/otherlode/testkit/shaded/export/") }) {
                "testkit jar is missing the relocated wire module"
            }
            val missing = bundledLicenseFiles.keys.filterNot { it in names }
            check(missing.isEmpty()) { "testkit jar is missing licence entries: $missing" }
            val notice = zip.getInputStream(zip.getEntry("META-INF/NOTICE")).bufferedReader().use { it.readLine() }
            check(notice == "Otherlode testkit") { "testkit jar's META-INF/NOTICE is not this module's own; first line is \"$notice\"" }
            val manifest = zip.getInputStream(zip.getEntry("META-INF/MANIFEST.MF")).bufferedReader().readText()
            check("Implementation-Version: ${project.version}" in manifest) { "testkit jar's manifest does not carry the project version" }
        }
    }
}

// A Java caller of every public query, compiled and never run.
val javaApiTest = sourceSets.create("javaApiTest")
dependencies {
    "javaApiTestImplementation"(sourceSets.main.get().output)
    "javaApiTestImplementation"(kotlin("stdlib"))
    "javaApiTestCompileOnly"("org.junit.jupiter:junit-jupiter-api:5.10.1")
}

// Never a test dependency, so no fixture class loads before a test installs instrumentation.
evaluationDependsOn(":fixtures-scala3")
evaluationDependsOn(":fixtures-scala2")
val scalaFixtureModules = listOf("scala3", "scala2")

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Djdk.attach.allowAttachSelf=true")
    dependsOn(scalaFixtureModules.map { ":fixtures-$it:classes" })
    doFirst {
        for (module in scalaFixtureModules) {
            val fixture = project(":fixtures-$module")
            systemProperty(
                "otherlode.fixtures.$module.dir",
                fixture.layout.buildDirectory
                    .dir("classes/scala/main")
                    .get()
                    .asFile.absolutePath,
            )
            systemProperty(
                "otherlode.fixtures.$module.classpath",
                fixture.configurations
                    .named("runtimeClasspath")
                    .get()
                    .asPath,
            )
        }
    }
}

// Kept apart from the agent's default port (4319). The plain `test` suite's tests self-attach and
// must stay that way.
val agentTestCollectorPort = 4329

// dep-used carries a pom.properties; dep-unused has none, so its identity comes from the filename,
// and nothing ever loads it.
val depUsed = sourceSets.create("depUsed")
val depUnused = sourceSets.create("depUnused")
val fixtureDependencyDir = layout.buildDirectory.dir("fixture-dependencies")
val depUsedJar by tasks.registering(Jar::class) {
    archiveFileName.set("dep-used-1.0.jar")
    destinationDirectory.set(fixtureDependencyDir)
    from(depUsed.output)
}
val depUnusedJar by tasks.registering(Jar::class) {
    archiveFileName.set("dep-unused-1.0.jar")
    destinationDirectory.set(fixtureDependencyDir)
    from(depUnused.output)
}

testing {
    suites {
        register<JvmTestSuite>("agentTest") {
            useJUnitJupiter("5.10.1")
            sources {
                kotlin.srcDir("src/agentTest/kotlin")
            }
            dependencies {
                // kotlin-stdlib arrives through kotlin-test-junit5.
                implementation(files(tasks.shadowJar))
                implementation("org.jetbrains.kotlin:kotlin-test-junit5:2.2.21")
                implementation(files(depUsedJar, depUnusedJar))
            }
            targets {
                all {
                    testTask.configure {
                        dependsOn(rootProject.tasks.named("shadowJar"))
                        doFirst {
                            val agentJar =
                                rootProject.tasks
                                    .named("shadowJar", Jar::class.java)
                                    .get()
                                    .archiveFile
                                    .get()
                                    .asFile
                            jvmArgs(
                                "-javaagent:${agentJar.absolutePath}=" +
                                    "includePackages=com.example.agenttarget," +
                                    "flushIntervalSeconds=1," +
                                    "staticBaselineEnabled=true," +
                                    "serviceName=testkit-agent-test," +
                                    "endpointsEnabled=false," +
                                    "exportUrl=http://localhost:$agentTestCollectorPort",
                                "-Dotherlode.testkit.port=$agentTestCollectorPort",
                            )
                        }
                    }
                }
            }
        }
    }
}

tasks.check {
    dependsOn(testing.suites.named("agentTest"))
    dependsOn(verifyTestkitJar)
    dependsOn(tasks.named("compileJavaApiTestJava"))
}

// A `shadow(...)` dependency would also write a Class-Path entry into the jar's manifest.
configurations.named("shadowRuntimeElements") {
    dependencies.add(project.dependencies.create("org.jetbrains.kotlin:kotlin-stdlib:2.0.21"))
}

val javadocJar by tasks.registering(Jar::class) {
    archiveBaseName.set("otherlode-testkit")
    archiveClassifier.set("javadoc")
    from(tasks.named("dokkaGeneratePublicationHtml"))
}

extra["otherlode.artifactId"] = "otherlode-testkit"
extra["otherlode.pomName"] = "Otherlode testkit"
extra["otherlode.pomDescription"] = "Assertions over the Otherlode agent's findings, for tests."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))

tasks.configureEach {
    if (this is AbstractPublishToMaven) dependsOn(verifyTestkitJar)
}
