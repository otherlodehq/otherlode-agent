import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.w3c.dom.Element
import java.nio.ByteBuffer
import java.time.Duration
import java.util.zip.CRC32
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    kotlin("jvm") version "2.2.21"
    id("com.gradleup.shadow") version "8.3.11"
    id("me.champeau.jmh") version "0.7.3"
    `maven-publish`
    signing
    id("com.gradleup.nmcp") version "1.6.2"
    id("com.gradleup.nmcp.aggregation") version "1.6.2"
}

group = "dev.otherlode"

repositories {
    mavenCentral()
}

val testJdk = providers.gradleProperty("otherlode.testJdk")

// Holds every shipped module and every module whose tests run on the test JVM.
val jdk17Projects =
    setOf(
        ":",
        ":bootstrap",
        ":asm-subroutines",
        ":wire",
        ":endpoints-api",
        ":endpoints-jaxrs",
        ":endpoints-javalin-6",
        ":endpoints-javalin-7",
        ":endpoints-jdk-httpserver",
        ":endpoints-ktor-2",
        ":endpoints-ktor-3",
        ":endpoints-micronaut-common",
        ":endpoints-micronaut",
        ":endpoints-micronaut-3",
        ":endpoints-vertx-web",
        ":endpoints-servlet",
        ":endpoints-otel-bridge",
        ":endpoints-spring-common",
        ":endpoints-spring-webmvc",
        ":endpoints-spring-webflux",
        ":testkit",
        ":demo",
        ":demo-spring",
        ":fixtures-scala3",
        ":fixtures-scala2",
        ":fixtures-kotlin-jvm-default-disable",
        ":fixtures-kotlin-class-sam",
    )
allprojects {
    if (path in jdk17Projects) {
        tasks.withType<JavaCompile>().matching { it.name != "compileTestJava21" }.configureEach { options.release.set(17) }
        tasks.withType<KotlinCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                freeCompilerArgs.add("-Xjdk-release=17")
            }
        }
    }
}

// JaCoCo's core library and its agent jar share one version, because CoverageAgentOrderTest reads
// the agent's execution data with the core's reader.
val jacocoVersion = "0.8.13"
allprojects {
    plugins.withType<JavaBasePlugin> {
        val toolchains = extensions.getByType<JavaToolchainService>()
        tasks.withType<Test>().matching { it.name != "micronaut5Test" && it.project.path != ":framework-markers" }.configureEach {
            if (testJdk.isPresent) {
                javaLauncher.set(toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(testJdk.get())) })
            }
            // These test JVMs start as an installed agent does, so agent loggers reach java.util.logging
            // whatever order the tests run in.
            val bootstrapJar = rootProject.project(":bootstrap").tasks.named<Jar>("jar").flatMap { it.archiveFile }
            inputs.file(bootstrapJar)
            jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Xbootclasspath/a:${bootstrapJar.get().asFile.absolutePath}") })
        }
    }
}

evaluationDependsOn(":bootstrap")
evaluationDependsOn(":asm-subroutines")
val asmSubroutinesJar = project(":asm-subroutines").tasks.named("shadowJar")
evaluationDependsOn(":fixtures-scala3")
evaluationDependsOn(":fixtures-scala2")
evaluationDependsOn(":fixtures-kotlin-jvm-default-disable")
evaluationDependsOn(":fixtures-kotlin-class-sam")
evaluationDependsOn(":fixtures-probe-window")
evaluationDependsOn(":fixtures-jpa")
evaluationDependsOn(":demo-spring")
evaluationDependsOn(":endpoints-ktor-3")

dependencies {
    // Shipping it in this jar too would put a second copy on the system loader.
    compileOnly(project(":bootstrap"))

    testCompileOnly(project(":bootstrap"))

    implementation("net.bytebuddy:byte-buddy:1.18.12")

    implementation(files(asmSubroutinesJar))

    implementation(project(":endpoints-api"))

    implementation(project(":endpoints-jdk-httpserver"))

    implementation(project(":endpoints-spring-common"))

    implementation(project(":endpoints-spring-webmvc"))

    implementation(project(":endpoints-spring-webflux"))

    implementation(project(":endpoints-javalin-6"))
    implementation(project(":endpoints-javalin-7"))

    implementation(project(":endpoints-micronaut-common"))

    implementation(project(":endpoints-micronaut-3"))

    implementation(project(":endpoints-micronaut"))

    implementation(project(":endpoints-ktor-2"))
    implementation(project(":endpoints-ktor-3"))

    implementation(project(":endpoints-jaxrs"))

    implementation(project(":endpoints-vertx-web"))

    implementation(project(":endpoints-servlet"))

    implementation(project(":endpoints-otel-bridge"))

    api(project(":wire"))

    testImplementation(kotlin("test"))

    // The version kotlin("test") already brings in for junit-jupiter-api.
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.1")
    testImplementation("net.bytebuddy:byte-buddy-agent:1.18.12")

    testImplementation("org.javassist:javassist:3.30.2-GA")

    testImplementation("org.jacoco:org.jacoco.core:$jacocoVersion")
}

kotlin {
    jvmToolchain(21)
}

// Java test fixtures that need release 21. The rest of the Java fixtures compile at 17.
val java21FixtureFiles =
    listOf(
        "com/example/target/SwitchJavaTarget.java",
        "com/example/target/ConditionJavaTarget.java",
        "com/example/target/keypairs/SwitchLabelsJavaV1.java",
        "com/example/target/keypairs/SwitchLabelsJavaV2.java",
    )
sourceSets.test {
    java.exclude(java21FixtureFiles)
}
val compileTestJava21 by tasks.registering(JavaCompile::class) {
    source = fileTree("src/test/java") { include(java21FixtureFiles) }
    classpath = sourceSets.test.get().compileClasspath
    // compileTestJava owns the class files of the other fixtures these name.
    options.sourcepath = files("src/test/java")
    options.compilerArgs.add("-implicit:none")
    destinationDirectory.set(layout.buildDirectory.dir("classes/java/test21"))
    options.release.set(21)
    javaCompiler.set(javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

interface InjectedFileSystem {
    @get:Inject
    val fs: FileSystemOperations
}

tasks.compileTestJava {
    val java21Classes = compileTestJava21.flatMap { it.destinationDirectory }
    inputs.dir(java21Classes).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("java21Fixtures")
    val injected = objects.newInstance<InjectedFileSystem>()
    val into = destinationDirectory
    doLast { injected.fs.copy { from(java21Classes).into(into) } }
}
tasks.withType<Test>().configureEach {
    // Gradle's class scan fails on a JVM that cannot define the release-21 fixtures.
    exclude(java21FixtureFiles.map { it.removeSuffix(".java") + "*" })
}

// The name must not end in ".jar", because shadowJar explodes every such file it copies. That would
// scatter the holder's classes into this jar as loose files and drop the resource itself.
val bootstrapResourcePath = "META-INF/otherlode/bootstrap-jar.bin"

val embedBootstrapJar by tasks.registering(Sync::class) {
    from(project(":bootstrap").tasks.named<Jar>("jar")) {
        into(bootstrapResourcePath.substringBeforeLast('/'))
        rename { bootstrapResourcePath.substringAfterLast('/') }
    }
    into(layout.buildDirectory.dir("generated-resources/bootstrap"))
}

sourceSets.main {
    resources.srcDir(embedBootstrapJar)
}

val bundledLicensesDir = layout.projectDirectory.dir("licenses")
val bundledLicenseEntries =
    fileTree(bundledLicensesDir).files.map { it.relativeTo(bundledLicensesDir.asFile).invariantSeparatorsPath }.sorted()

val verifyAgentJar by tasks.registering {
    dependsOn(tasks.shadowJar)
    val jarFile = tasks.shadowJar.flatMap { it.archiveFile }
    inputs.file(jarFile)
    inputs.property("bundledLicenseEntries", bundledLicenseEntries)
    doLast {
        ZipFile(jarFile.get().asFile).use { zip ->
            val names =
                zip
                    .entries()
                    .asSequence()
                    .map { it.name }
                    .toList()
            check(bootstrapResourcePath in names) {
                "agent jar is missing the embedded bootstrap holder at $bootstrapResourcePath"
            }
            val inliner = "dev/otherlode/shaded/bytebuddy/jar/asm/commons/JSRInlinerAdapter.class"
            check(inliner in names) { "agent jar is missing ASM's subroutine inliner at $inliner" }
            val analyser = "dev/otherlode/shaded/bytebuddy/jar/asm/commons/AnalyzerAdapter.class"
            check(analyser in names) { "agent jar is missing ASM's frame analyser at $analyser" }
            val tooNew =
                names
                    .filter { it.endsWith(".class") }
                    .filter {
                        val allowed =
                            Regex("^META-INF/versions/(\\d+)/").find(it)?.let { match ->
                                match.groupValues[1].toInt() + 44
                            } ?: jdk17ClassVersion
                        classFileMajorVersion(zip.getInputStream(zip.getEntry(it)).use { stream -> stream.readNBytes(8) }) > allowed
                    }
            check(tooNew.isEmpty()) {
                "agent jar has classes above their allowed class-file version " +
                    "($jdk17ClassVersion, or 44+N under META-INF/versions/N/): ${tooNew.take(10)}"
            }
            val embedded = ZipInputStream(zip.getInputStream(zip.getEntry(bootstrapResourcePath)))
            embedded.use { holder ->
                generateSequence { holder.nextEntry }.filter { it.name.endsWith(".class") }.forEach { entry ->
                    check(classFileMajorVersion(holder.readNBytes(8)) <= jdk17ClassVersion) {
                        "the embedded bootstrap holder's ${entry.name} is above class-file version $jdk17ClassVersion"
                    }
                }
            }
            val naming =
                names
                    .filter { it.endsWith(".class") }
                    .filter { "java/util/logging/Logger" in String(zip.getInputStream(zip.getEntry(it)).use { s -> s.readBytes() }, Charsets.ISO_8859_1) }
            check(naming.isEmpty()) {
                "agent classes must not name java.util.logging.Logger, whose first use creates the application's LogManager: ${naming.take(10)}"
            }
            val loose = names.filter { it.startsWith("dev/otherlode/bootstrap/") && it.endsWith(".class") }
            check(loose.isEmpty()) {
                "agent jar must not carry the bootstrap holder as loose classes, found: $loose"
            }
            val notice = checkNotNull(zip.getEntry("META-INF/NOTICE")) { "agent jar is missing META-INF/NOTICE" }
            val noticeFirstLine = zip.getInputStream(notice).bufferedReader().use { it.readLine() }
            check(noticeFirstLine == "Otherlode") {
                "agent jar's META-INF/NOTICE is not this project's own; first line is \"$noticeFirstLine\""
            }
            check("META-INF/LICENSE" in names) { "agent jar is missing META-INF/LICENSE" }
            val missingLicenses = bundledLicenseEntries.map { "META-INF/licenses/$it" }.filterNot { it in names }
            check(missingLicenses.isEmpty()) {
                "agent jar is missing vendored third-party licence entries: $missingLicenses"
            }

            val relocatedLiteralPrefixes = listOf("dev.otherlode.shaded.", "dev/otherlode/shaded/", "Ldev/otherlode/shaded/")
            val thirdPartyShaded = listOf("bytebuddy", "kotlin", "protobuf", "intellij", "annotations").map { "dev/otherlode/shaded/$it/" }
            // A Kotlin null check on a parameter named kotlinKind carries the name as its message.
            val intendedRewrites = mapOf("dev/otherlode/AgentLogger" to setOf("dev.otherlode.shaded.jul.Logger"))
            val parameterNameRewrite = "dev.otherlode.shaded.kotlinKind"
            val rewrittenLiterals =
                names
                    .filter { it.endsWith(".class") && it.startsWith("dev/otherlode/") && thirdPartyShaded.none { prefix -> it.startsWith(prefix) } }
                    .flatMap { entryName ->
                        val shape = parseClassShape(zip.getInputStream(zip.getEntry(entryName)).use { it.readBytes() })
                        shape.stringConstants
                            .map { constant -> constant.trimStart('[') to constant }
                            .filter { (stripped, _) -> relocatedLiteralPrefixes.any { stripped.startsWith(it) } }
                            .map { (_, constant) -> constant }
                            .filter { constant -> constant != parameterNameRewrite && constant !in intendedRewrites[shape.name].orEmpty() }
                            .map { constant -> "${shape.name}: \"$constant\"" }
                    }
            check(rewrittenLiterals.isEmpty()) {
                "string constants in the agent's own classes were rewritten by the shadow relocation; build the " +
                    "value at run time so no compile-time constant starts with a relocated package name:\n  " +
                    rewrittenLiterals.joinToString("\n  ")
            }

            // Advice is inlined, so anything else it touches resolves through the target's classloader and can
            // fail with NoClassDefFoundError. A private helper is a real call at the woven site, and a lambda,
            // a switch over an enum or an assert compiles to a synthetic member.
            val adviceSeams =
                listOf(
                    "dev/otherlode/bootstrap/OtherlodeEndpoints",
                    "dev/otherlode/bootstrap/OtherlodeVerifierMessages",
                    "dev/otherlode/bootstrap/OtherlodeShutdownHooks",
                )
            val advicePackages = listOf("dev/otherlode/endpoints/", "dev/otherlode/verifier/", "dev/otherlode/shutdown/")
            val adviceClassNames = names.filter { name -> advicePackages.any { name.startsWith(it) } && name.endsWith(".class") }
            val adviceProblems =
                adviceClassNames.flatMap { entryName ->
                    val shape = parseClassShape(zip.getInputStream(zip.getEntry(entryName)).use { it.readBytes() })
                    val problems = mutableListOf<String>()
                    if (shape.utf8Constants.any { "dev/otherlode/shaded/kotlin" in it }) {
                        problems += "references the shaded Kotlin stdlib"
                    }
                    val foreignAgentClasses =
                        shape.referencedClasses.filter {
                            it.startsWith("dev/otherlode/") &&
                                it != shape.name &&
                                adviceSeams.none { seam -> it == seam || it.startsWith("$seam$") } &&
                                !it.startsWith("dev/otherlode/shaded/bytebuddy/")
                        }
                    if (foreignAgentClasses.isNotEmpty()) problems += "references agent classes $foreignAgentClasses"
                    if (shape.selfMethodCalls.isNotEmpty()) problems += "calls its own methods ${shape.selfMethodCalls}"
                    if (shape.syntheticMembers.isNotEmpty()) problems += "declares synthetic members ${shape.syntheticMembers}"
                    problems.map { "${shape.name}: $it" }
                }
            check(adviceProblems.isEmpty()) {
                "advice classes must be self-contained (see the comment in verifyAgentJar):\n  " +
                    adviceProblems.joinToString("\n  ")
            }
        }
    }
}

val jdk17ClassVersion = 61

fun classFileMajorVersion(header: ByteArray): Int = ByteBuffer.wrap(header).getShort(6).toInt() and 0xFFFF

class ClassShape(
    val name: String,
    val utf8Constants: List<String>,
    val stringConstants: List<String>,
    val referencedClasses: Set<String>,
    val selfMethodCalls: Set<String>,
    val syntheticMembers: List<String>,
)

fun parseClassShape(bytes: ByteArray): ClassShape {
    val buf = ByteBuffer.wrap(bytes)

    fun u2(): Int = buf.short.toInt() and 0xFFFF
    check(buf.int == 0xCAFEBABE.toInt()) { "not a class file" }
    u2()
    u2()
    val poolCount = u2()
    val utf8 = arrayOfNulls<String>(poolCount)
    val classNameIndex = IntArray(poolCount)
    val refClassIndex = IntArray(poolCount)
    val refNameAndTypeIndex = IntArray(poolCount)
    val nameAndTypeNameIndex = IntArray(poolCount)
    val stringIndex = IntArray(poolCount)
    val methodRefs = mutableListOf<Int>()
    var i = 1
    while (i < poolCount) {
        when (val tag = buf.get().toInt()) {
            1 -> {
                val length = u2()
                val raw = ByteArray(length)
                buf.get(raw)
                utf8[i] = String(raw, Charsets.UTF_8)
            }

            3, 4 -> {
                buf.int
            }

            5, 6 -> {
                buf.long
                i++
            }

            7 -> {
                classNameIndex[i] = u2()
            }

            8 -> {
                stringIndex[i] = u2()
            }

            16, 19, 20 -> {
                u2()
            }

            9, 10, 11 -> {
                refClassIndex[i] = u2()
                refNameAndTypeIndex[i] = u2()
                if (tag == 10 || tag == 11) methodRefs += i
            }

            12 -> {
                nameAndTypeNameIndex[i] = u2()
                u2()
            }

            15 -> {
                buf.get()
                u2()
            }

            17, 18 -> {
                u2()
                u2()
            }

            else -> {
                error("unknown constant pool tag $tag")
            }
        }
        i++
    }
    u2()
    val thisClass = u2()
    u2()
    repeat(u2()) { u2() }
    val name = checkNotNull(utf8[classNameIndex[thisClass]])
    val synthetic = mutableListOf<String>()
    repeat(2) {
        repeat(u2()) {
            val access = u2()
            val memberName = checkNotNull(utf8[u2()])
            u2()
            repeat(u2()) {
                u2()
                val attributeLength = buf.int
                buf.position(buf.position() + attributeLength)
            }
            if (access and 0x1000 != 0 || memberName.startsWith("lambda$") || memberName.startsWith("$")) {
                synthetic += memberName
            }
        }
    }
    val referencedClasses =
        (1 until poolCount).filter { classNameIndex[it] != 0 }.mapTo(
            HashSet(),
        ) { checkNotNull(utf8[classNameIndex[it]]) }
    val selfMethodCalls =
        methodRefs
            .filter { utf8[classNameIndex[refClassIndex[it]]] == name }
            .mapTo(HashSet()) { checkNotNull(utf8[nameAndTypeNameIndex[refNameAndTypeIndex[it]]]) }
    val stringConstants = (1 until poolCount).filter { stringIndex[it] != 0 }.map { checkNotNull(utf8[stringIndex[it]]) }
    return ClassShape(name, utf8.filterNotNull(), stringConstants, referencedClasses, selfMethodCalls, synthetic)
}

tasks.shadowJar {
    finalizedBy(verifyAgentJar)
}

// No fixture project goes on the test classpath, since JUnit discovery would load its classes
// before install() wires up instrumentation.
val scala3FixtureClassesDir = project(":fixtures-scala3").layout.buildDirectory.dir("classes/scala/main")
val scala3FixtureRuntimeClasspath = project(":fixtures-scala3").configurations.named("runtimeClasspath")
val scala2FixtureClassesDir = project(":fixtures-scala2").layout.buildDirectory.dir("classes/scala/main")
val scala2FixtureRuntimeClasspath = project(":fixtures-scala2").configurations.named("runtimeClasspath")
val jvmDefaultDisableFixtureClassesDir =
    project(":fixtures-kotlin-jvm-default-disable").layout.buildDirectory.dir("classes/kotlin/main")
val jvmDefaultDisableFixtureRuntimeClasspath =
    project(":fixtures-kotlin-jvm-default-disable").configurations.named("runtimeClasspath")
val classSamFixtureClassesDir = project(":fixtures-kotlin-class-sam").layout.buildDirectory.dir("classes/kotlin/main")

val probeWindowProject = project(":fixtures-probe-window")
val probeWindowForms = listOf("legacy", "modern")
val probeWindowClassesDirs =
    probeWindowForms.associateWith { form ->
        listOf("java", "kotlin").map { probeWindowProject.layout.buildDirectory.dir("classes/$it/$form") }
    }
val probeWindowKotlinStdlib = probeWindowProject.configurations.named("kotlinStdlib")

val kotlincMatrix = providers.gradleProperty("otherlode.matrix.kotlinc").get().split(",")
val javacMatrix = providers.gradleProperty("otherlode.matrix.javac").get().split(",")
val kotlincSuspendsSource =
    project(":fixtures-kotlinc").file("src/main/kotlin/com/example/target/kotlinc/Suspends.kt")
val kotlincMatrixClassesDirs =
    kotlincMatrix.associateWith { project(":fixtures-kotlinc").layout.buildDirectory.dir("classes/kotlinc/$it") }
val kotlincK1Matrix =
    providers
        .gradleProperty("otherlode.matrix.kotlincK1")
        .get()
        .split(",")
        .toSet()
val kotlincSerializationKeys =
    kotlincMatrix.flatMap { version -> if (version in kotlincK1Matrix) listOf(version, "$version-k1") else listOf(version) }
val kotlincSerializationClassesDirs =
    kotlincSerializationKeys.associateWith { project(":fixtures-kotlinc").layout.buildDirectory.dir("classes/serialization/$it") }
val kotlincMixedClassesDirs =
    kotlincMatrix.associateWith { project(":fixtures-kotlinc").layout.buildDirectory.dir("classes/mixed/$it") }
val javacMatrixClassesDirs =
    javacMatrix.associateWith { project(":fixtures-javac").layout.buildDirectory.dir("classes/javac/$it") }
val jpaFixtureLibraries = listOf("ebean", "eclipselink", "openjpa")
val jpaFixtureClassesDirs =
    jpaFixtureLibraries.associateWith { project(":fixtures-jpa").layout.buildDirectory.dir("classes/$it") }
val jpaFixtureRawDirs =
    jpaFixtureLibraries.associateWith { project(":fixtures-jpa").layout.buildDirectory.dir("raw/$it") }
val jpaFixtureRuntimeClasspaths =
    jpaFixtureLibraries.associateWith {
        project(":fixtures-jpa").configurations.named(
            when (it) {
                "ebean" -> "ebeanRuntime"
                "eclipselink" -> "eclipseLinkRuntime"
                else -> "openJpaRuntime"
            },
        )
    }
val scalacMatrix = providers.gradleProperty("otherlode.matrix.scalac").get().split(",")
val scalacMatrixClassesDirs =
    scalacMatrix.associateWith { project(":fixtures-scalac").layout.buildDirectory.dir("classes/scalac/$it") }
val scalacMatrixVersionFiles =
    scalacMatrix.associateWith { project(":fixtures-scalac").layout.buildDirectory.file("scalac-version/$it.txt") }

// The name stays clear of `jacocoAgent`, which the `jacoco` plugin creates when an init script
// applies it.
val coverageAgentOrderJacoco by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    coverageAgentOrderJacoco("org.jacoco:org.jacoco.agent:$jacocoVersion:runtime")
}

val loggerBridgeTestJars by configurations.creating {
    isCanBeConsumed = false
}
dependencies {
    loggerBridgeTestJars("org.apache.logging.log4j:log4j-api:2.24.3")
    loggerBridgeTestJars("org.apache.logging.log4j:log4j-core:2.24.3")
    loggerBridgeTestJars("org.apache.logging.log4j:log4j-jpl:2.24.3")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Djdk.attach.allowAttachSelf=true")
    dependsOn(
        ":fixtures-scala3:classes",
        ":fixtures-scala2:classes",
        ":fixtures-kotlin-jvm-default-disable:classes",
        ":fixtures-kotlin-class-sam:classes",
        ":fixtures-probe-window:classes",
        ":fixtures-kotlinc:classes",
        ":fixtures-javac:classes",
        ":fixtures-scalac:classes",
        ":fixtures-jpa:classes",
    )
    // Without these inputs, a fixture change would leave the test task up to date.
    listOf(
        "scala3" to scala3FixtureClassesDir,
        "scala2" to scala2FixtureClassesDir,
        "jvmDefaultDisable" to jvmDefaultDisableFixtureClassesDir,
        "classSam" to classSamFixtureClassesDir,
    ).plus(kotlincMatrixClassesDirs.map { (version, dir) -> "kotlinc$version" to dir })
        .plus(kotlincSerializationClassesDirs.map { (key, dir) -> "kotlincSerialization$key" to dir })
        .plus(kotlincMixedClassesDirs.map { (version, dir) -> "kotlincMixed$version" to dir })
        .plus(javacMatrixClassesDirs.map { (version, dir) -> "javac$version" to dir })
        .plus(scalacMatrixClassesDirs.map { (version, dir) -> "scalac$version" to dir })
        .plus(jpaFixtureClassesDirs.map { (name, dir) -> "jpa$name" to dir })
        .forEach { (name, dir) ->
            inputs.dir(dir).withPropertyName("fixtureClasses.$name").withPathSensitivity(PathSensitivity.RELATIVE)
        }
    scalacMatrixVersionFiles.forEach { (version, file) ->
        inputs.file(file).withPropertyName("scalacVersion.$version").withPathSensitivity(PathSensitivity.NONE)
    }
    probeWindowClassesDirs.forEach { (form, dirs) ->
        dirs.forEachIndexed { index, dir ->
            inputs.dir(dir).withPropertyName("fixtureClasses.probeWindow.$form.$index").withPathSensitivity(PathSensitivity.RELATIVE)
        }
    }
    // KotlincMatrixTest reads line numbers from this source's marker comments.
    inputs.file(kotlincSuspendsSource).withPropertyName("kotlincSuspendsSource").withPathSensitivity(PathSensitivity.NONE)
    dependsOn(tasks.shadowJar)
    val shadedAgentJar = tasks.shadowJar.flatMap { it.archiveFile }
    inputs.file(shadedAgentJar)
    inputs.files(coverageAgentOrderJacoco).withPropertyName("coverageAgentOrderJacoco")
    inputs.files(loggerBridgeTestJars).withPropertyName("loggerBridgeTestJars")
    doFirst {
        systemProperty("otherlode.agent.shadedJar", shadedAgentJar.get().asFile.absolutePath)
        systemProperty("otherlode.agent.version", project.version.toString())
        systemProperty("otherlode.test.log4jJars", loggerBridgeTestJars.files.joinToString(File.pathSeparator) { it.absolutePath })
        systemProperty("otherlode.jacoco.agentJar", coverageAgentOrderJacoco.singleFile.absolutePath)
        systemProperty("otherlode.fixtures.scala3.dir", scala3FixtureClassesDir.get().asFile.absolutePath)
        systemProperty("otherlode.fixtures.scala3.classpath", scala3FixtureRuntimeClasspath.get().asPath)
        systemProperty("otherlode.fixtures.scala2.dir", scala2FixtureClassesDir.get().asFile.absolutePath)
        systemProperty("otherlode.fixtures.scala2.classpath", scala2FixtureRuntimeClasspath.get().asPath)
        systemProperty(
            "otherlode.fixtures.jvmdefaultdisable.dir",
            jvmDefaultDisableFixtureClassesDir.get().asFile.absolutePath,
        )
        systemProperty(
            "otherlode.fixtures.jvmdefaultdisable.classpath",
            jvmDefaultDisableFixtureRuntimeClasspath.get().asPath,
        )
        systemProperty("otherlode.fixtures.classsam.dir", classSamFixtureClassesDir.get().asFile.absolutePath)
        probeWindowClassesDirs.forEach { (form, dirs) ->
            systemProperty(
                "otherlode.fixtures.probewindow.$form.dirs",
                dirs.joinToString(File.pathSeparator) { it.get().asFile.absolutePath },
            )
        }
        systemProperty("otherlode.fixtures.probewindow.kotlinStdlib", probeWindowKotlinStdlib.get().singleFile.absolutePath)
        systemProperty("otherlode.fixtures.kotlinc.versions", kotlincMatrix.joinToString(","))
        systemProperty("otherlode.fixtures.javac.versions", javacMatrix.joinToString(","))
        systemProperty("otherlode.fixtures.scalac.versions", scalacMatrix.joinToString(","))
        scalacMatrixClassesDirs.forEach { (version, dir) ->
            systemProperty("otherlode.fixtures.scalac.$version.dir", dir.get().asFile.absolutePath)
        }
        scalacMatrixVersionFiles.forEach { (version, file) ->
            systemProperty("otherlode.fixtures.scalac.$version.versionFile", file.get().asFile.absolutePath)
        }
        jpaFixtureClassesDirs.forEach { (name, dir) ->
            systemProperty("otherlode.fixtures.jpa.$name.dir", dir.get().asFile.absolutePath)
            systemProperty(
                "otherlode.fixtures.jpa.$name.rawDir",
                jpaFixtureRawDirs
                    .getValue(name)
                    .get()
                    .asFile.absolutePath,
            )
            systemProperty("otherlode.fixtures.jpa.$name.classpath", jpaFixtureRuntimeClasspaths.getValue(name).get().asPath)
        }
        systemProperty("otherlode.fixtures.kotlinc.suspendsSource", kotlincSuspendsSource.absolutePath)
        kotlincMatrixClassesDirs.forEach { (version, dir) ->
            systemProperty("otherlode.fixtures.kotlinc.$version.dir", dir.get().asFile.absolutePath)
        }
        kotlincMixedClassesDirs.forEach { (version, dir) ->
            systemProperty("otherlode.fixtures.kotlincMixed.$version.dir", dir.get().asFile.absolutePath)
        }
        systemProperty("otherlode.fixtures.kotlincSerialization.keys", kotlincSerializationKeys.joinToString(","))
        kotlincSerializationClassesDirs.forEach { (key, dir) ->
            systemProperty("otherlode.fixtures.kotlincSerialization.$key.dir", dir.get().asFile.absolutePath)
        }
        javacMatrixClassesDirs.forEach { (version, dir) ->
            systemProperty("otherlode.fixtures.javac.$version.dir", dir.get().asFile.absolutePath)
        }
        systemProperty(
            "otherlode.demo.serverMainSource",
            project(":demo").file("src/main/kotlin/com/example/demo/server/DemoServerMain.kt").absolutePath,
        )
    }
}

val benchmarkCorpora: Map<String, Map<String, FileCollection>> =
    mapOf(
        "ktor-server-core" to
            mapOf(
                "jar" to
                    project(":endpoints-ktor-3")
                        .configurations
                        .getByName("testRuntimeClasspath")
                        .filter { it.name.startsWith("ktor-server-core-jvm-") },
            ),
        "demo" to mapOf("main" to files(project(":demo").layout.buildDirectory.dir("classes/kotlin/main")).builtBy(":demo:classes")),
        "demo-spring" to
            mapOf(
                "main" to files(project(":demo-spring").layout.buildDirectory.dir("classes/kotlin/main")).builtBy(":demo-spring:classes"),
            ),
        "scala" to
            mapOf(
                "fixtures-scala2" to files(scala2FixtureClassesDir).builtBy(":fixtures-scala2:classes"),
                "fixtures-scala3" to files(scala3FixtureClassesDir).builtBy(":fixtures-scala3:classes"),
            ),
        "spring-webmvc" to
            mapOf(
                "jar" to
                    project(":demo-spring")
                        .configurations
                        .getByName("runtimeClasspath")
                        .filter { it.name.startsWith("spring-webmvc-") },
            ),
    )
val benchmarkCorpusFiles = files(benchmarkCorpora.values.flatMap { it.values })

fun benchmarkCorpusProperties(): Map<String, String> =
    benchmarkCorpora
        .flatMap { (corpus, classSets) ->
            classSets.map { (classSet, files) ->
                val path = files.asPath
                // JMH joins the fork's JVM arguments with spaces, so a space in a path would split it.
                check(' ' !in path) { "benchmark corpus $corpus.$classSet has a space in its path: $path" }
                "otherlode.benchmark.corpus.$corpus.$classSet" to path
            }
        }.toMap()

val codeSizeClasspaths: Map<String, FileCollection> =
    mapOf(
        "demo.main" to files({ project(":demo").configurations.getByName("runtimeClasspath") }),
        "demo-spring.main" to files({ project(":demo-spring").configurations.getByName("runtimeClasspath") }),
        "spring-webmvc.jar" to files({ project(":demo-spring").configurations.getByName("runtimeClasspath") }),
        "ktor-server-core.jar" to files({ project(":endpoints-ktor-3").configurations.getByName("testRuntimeClasspath") }),
        "scala.fixtures-scala2" to files({ project(":fixtures-scala2").configurations.getByName("runtimeClasspath") }),
        "scala.fixtures-scala3" to files({ project(":fixtures-scala3").configurations.getByName("runtimeClasspath") }),
    )

tasks.test {
    inputs.files(benchmarkCorpusFiles).withPropertyName("benchmarkCorpora")
    inputs.files(codeSizeClasspaths.values).withPropertyName("codeSizeClasspaths")
    val codeSizeReport = layout.buildDirectory.file("reports/code-size/limits.txt")
    outputs.file(codeSizeReport).withPropertyName("codeSizeReport")
    doFirst {
        systemProperties(benchmarkCorpusProperties())
        codeSizeClasspaths.forEach { (classSet, files) ->
            systemProperty("otherlode.codesize.classpath.$classSet", files.asPath)
        }
        systemProperty("otherlode.codesize.report", codeSizeReport.get().asFile.absolutePath)
    }
}

// `-PupdateVisibilityBaseline` has VisibilityFloorTest rewrite its committed baseline.
tasks.test {
    if (project.hasProperty("updateVisibilityBaseline")) {
        systemProperty("otherlode.visibility.baselineSource", file("src/test/resources/visibility-baseline.txt").absolutePath)
    }
}

// With escape analysis on, C2 removes a boxed value or a varargs array that stays inside the call,
// so a probe that made one would read as allocating nothing.
val probeAllocationTest by tasks.registering(Test::class) {
    description = "Checks that the woven probes allocate nothing, with escape analysis off."
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:-DoEscapeAnalysis")
    filter { includeTestsMatching("dev.otherlode.benchmark.ProbeAllocationTest") }
}
tasks.test { filter { excludeTestsMatching("dev.otherlode.benchmark.ProbeAllocationTest") } }
tasks.check { dependsOn(probeAllocationTest) }

// The hook weaves a boot class for the rest of the JVM, so each test class needs a JVM of its own.
// `-XX:+BytecodeVerificationLocal` makes the JVM verify the woven boot class, which it skips by default.
val verifierHookTest by tasks.registering(Test::class) {
    description = "Weaves java.lang.VerifyError in a JVM per test class and checks what the hook reads."
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    forkEvery = 1
    jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+UnlockDiagnosticVMOptions", "-XX:+BytecodeVerificationLocal")
    filter { includeTestsMatching("dev.otherlode.instrumentation.VerifierHook*Test") }
}
tasks.test { filter { excludeTestsMatching("dev.otherlode.instrumentation.VerifierHook*Test") } }
tasks.check { dependsOn(verifierHookTest) }

dependencies {
    "jmhCompileOnly"(project(":bootstrap"))
    "jmhImplementation"("net.bytebuddy:byte-buddy-agent:1.18.12")
}

jmh {
    jmhVersion.set("1.37")
    includeTests.set(false)
    profilers.add("gc")
    jvmArgsAppend.add("-Djdk.attach.allowAttachSelf=true")
    providers.gradleProperty("otherlode.benchmark.include").orNull?.let { includes.add(it) }
    jvmArgsAppend.addAll(provider { benchmarkCorpusProperties().map { (key, value) -> "-D$key=$value" } })
    jvmArgsAppend.addAll(
        provider {
            codeSizeClasspaths.map { (classSet, files) ->
                val path = files.asPath
                check(' ' !in path) { "code-size classpath $classSet has a space in its path: $path" }
                "-Dotherlode.codesize.classpath.$classSet=$path"
            }
        },
    )
    providers.gradleProperty("otherlode.benchmark.corpus").orNull?.let { selected ->
        benchmarkParameters.put("corpus", objects.listProperty<String>().value(selected.split(',').map { it.trim() }))
    }
}

tasks.named("jmh") {
    inputs.files(benchmarkCorpusFiles).withPropertyName("benchmarkCorpora")
    inputs.files(codeSizeClasspaths.values).withPropertyName("codeSizeClasspaths")
}

sourceSets.test {
    compileClasspath += sourceSets["jmh"].output
    runtimeClasspath += sourceSets["jmh"].output
}

val agentMainClass = "dev.otherlode.Agent"

// Without its own classifier this jar would overwrite the shaded agent jar at the same path, and
// disabling the task breaks `project(":")` consumers such as the demo.
tasks.jar {
    archiveClassifier.set("plain")
}

tasks.shadowJar {
    archiveClassifier.set("")

    // Without this, shadow keeps one EndpointModule services file and drops every other module.
    mergeServiceFiles()

    relocate("net.bytebuddy", "dev.otherlode.shaded.bytebuddy")

    relocate("com.google.protobuf", "dev.otherlode.shaded.protobuf")

    // protobuf-java asks for a java.util.logging.Logger in static initialisers, which creates the
    // LogManager before the application can choose its own.
    relocate("java.util.logging.Logger", "dev.otherlode.shaded.jul.Logger")

    // A test classpath that carries the testkit carries the wire module too, possibly at another
    // version. Unrelocated, the agent would link against that copy instead of its own.
    relocate("dev.otherlode.export", "dev.otherlode.shaded.export")
    relocate("dev.otherlode.proto", "dev.otherlode.shaded.proto")
    relocate("dev.otherlode.registry.RouteTemplateNormalizer", "dev.otherlode.shaded.registry.RouteTemplateNormalizer")

    relocate("kotlin", "dev.otherlode.shaded.kotlin")

    relocate("org.jetbrains.annotations", "dev.otherlode.shaded.annotations")
    relocate("org.intellij.lang.annotations", "dev.otherlode.shaded.intellij.annotations")

    // These excludes drop every dependency's own LICENSE and NOTICE. They do not match the from()
    // blocks below, which shadow matches against their own source paths.
    exclude("META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/licenses/**")
    from(layout.projectDirectory.files("LICENSE", "NOTICE")) {
        into("META-INF")
    }
    from(bundledLicensesDir) {
        into("META-INF/licenses")
    }

    manifest {
        attributes(
            "Premain-Class" to agentMainClass,
            "Implementation-Version" to project.version,
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
        )
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

// The Portal accepts a javadoc jar holding only a README for code with no public API.
val agentJavadocReadme = layout.buildDirectory.file("javadoc-readme/README.md")
val writeJavadocReadme by tasks.registering {
    val readme = agentJavadocReadme
    outputs.file(readme)
    doLast {
        readme.get().asFile.writeText(
            "otherlode-agent is a -javaagent jar and has no public API to document.\n" +
                "See https://github.com/otherlodehq/otherlode-agent for its configuration and use.\n",
        )
    }
}
val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
    from(writeJavadocReadme)
}

extra["otherlode.artifactId"] = "otherlode-agent"
extra["otherlode.pomName"] = "Otherlode agent"
extra["otherlode.pomDescription"] = "A Java agent that counts the methods, branches and endpoints an application runs."
apply(from = layout.projectDirectory.file("gradle/publishing.gradle.kts"))

dependencies {
    nmcpAggregation(project(":"))
    nmcpAggregation(project(":testkit"))
}

nmcpAggregation {
    centralPortal {
        username.set(providers.environmentVariable("MAVEN_CENTRAL_USERNAME"))
        password.set(providers.environmentVariable("MAVEN_CENTRAL_PASSWORD"))
        val publishing = providers.gradleProperty("otherlode.centralPublishingType").getOrElse("USER_MANAGED")
        publishingType.set(publishing)
        publicationName.set("otherlode $version")
        // nmcp's ten-minute default can end the upload task while the Portal is still validating. Under
        // USER_MANAGED nmcp rejects a publishing timeout, and only after it has uploaded and validated.
        validationTimeout.set(Duration.ofMinutes(60))
        if (publishing == "AUTOMATIC") publishingTimeout.set(Duration.ofMinutes(60))
    }
    // With this false, the upload carries .md5, .sha1 and .sha512 beside every artifact and no checksum
    // beside a signature, which is what the Portal asks for.
    publishAllChecksums.set(false)
}

// `shadowJar` finalising `verifyAgentJar` does not make an upload wait for it.
tasks.configureEach {
    if (this is AbstractPublishToMaven || name.startsWith("nmcpZip") || name.startsWith("nmcpPublish") ||
        name.startsWith("publishAggregation") || name.startsWith("publishAllPublications")
    ) {
        dependsOn(verifyAgentJar)
        if (name.contains("Aggregation")) dependsOn(":testkit:verifyTestkitJar")
    }
}

val verifyPublication by tasks.registering {
    group = "verification"
    description = "Builds the Central Portal deployment zip and checks its contents."
    val zipFile = tasks.named<Zip>("nmcpZipAggregation").flatMap { it.archiveFile }
    val agentJar = tasks.shadowJar.flatMap { it.archiveFile }
    // Gradle resolves this when it builds the task graph, by which time the testkit project is configured.
    val testkitJar =
        providers.provider {
            project(":testkit")
                .tasks
                .named<Jar>("shadowJar")
                .get()
                .archiveFile
                .get()
        }
    dependsOn(tasks.named("nmcpZipAggregation"), ":testkit:shadowJar")
    inputs.file(zipFile)
    inputs.file(agentJar)
    inputs.file(testkitJar)
    val signed = providers.environmentVariable("SIGNING_KEY").filter { it.isNotBlank() }.isPresent
    inputs.property("signed", signed)
    val versionText = project.version.toString()
    doLast {
        val components = mapOf("otherlode-agent" to "agent", "otherlode-testkit" to "testkit")
        val versionPattern = Regex.escape(versionText)
        ZipFile(zipFile.get().asFile).use { zip ->
            val names =
                zip
                    .entries()
                    .asSequence()
                    .filterNot { it.isDirectory }
                    .map { it.name }
                    .toSet()
            val directories = names.map { it.substringBeforeLast('/') }.toSet()
            val expectedDirectories = components.keys.map { "dev/otherlode/$it/$versionText" }.toSet()
            check(directories == expectedDirectories) {
                "deployment holds $directories, expected exactly $expectedDirectories"
            }

            fun text(name: String) = zip.getInputStream(zip.getEntry(name)).bufferedReader().use { it.readText() }

            for (artifactId in components.keys) {
                // A SNAPSHOT deployment names its files with a timestamp in place of the version.
                val fileVersion =
                    names
                        .mapNotNull {
                            Regex(
                                "^dev/otherlode/$artifactId/$versionPattern/$artifactId-(.+)\\.pom$",
                            ).find(it)?.groupValues?.get(1)
                        }.singleOrNull() ?: error("$artifactId has no single .pom in the deployment")
                val base = "dev/otherlode/$artifactId/$versionText/$artifactId-$fileVersion"
                val artifacts =
                    listOf(".jar", "-sources.jar", "-javadoc.jar", ".pom", ".module").map { base + it }
                val missing = artifacts.filterNot { it in names }
                check(missing.isEmpty()) { "$artifactId is missing $missing" }
                for (artifact in artifacts) {
                    for (checksum in listOf("md5", "sha1")) {
                        check("$artifact.$checksum" in names) { "no .$checksum beside $artifact" }
                    }
                    if (signed) check("$artifact.asc" in names) { "no .asc beside $artifact" }
                }
                if (!signed) {
                    val signatures = names.filter { it.endsWith(".asc") }
                    check(signatures.isEmpty()) { "unsigned build carries signatures: $signatures" }
                }

                val pom =
                    DocumentBuilderFactory
                        .newInstance()
                        .newDocumentBuilder()
                        .parse(zip.getInputStream(zip.getEntry("$base.pom")))
                        .documentElement

                fun field(path: String): String? {
                    var node: Element? = pom
                    for (part in path.split('/')) {
                        node =
                            generateSequence(node?.firstChild) { it.nextSibling }
                                .filterIsInstance<Element>()
                                .firstOrNull { it.localName == part || it.tagName == part }
                    }
                    return node?.textContent?.trim()
                }
                val required =
                    mapOf(
                        "groupId" to "dev.otherlode",
                        "artifactId" to artifactId,
                        "version" to versionText,
                        "url" to "https://github.com/otherlodehq/otherlode-agent",
                        "licenses/license/name" to "The Apache License, Version 2.0",
                        "licenses/license/url" to "https://www.apache.org/licenses/LICENSE-2.0.txt",
                        "developers/developer/id" to "otherlode",
                        "developers/developer/name" to "Luke Killick",
                        "developers/developer/email" to "releases@otherlode.dev",
                        "developers/developer/organization" to "Otherlode",
                        "developers/developer/organizationUrl" to "https://otherlode.dev",
                        "scm/url" to "https://github.com/otherlodehq/otherlode-agent",
                        "scm/connection" to "scm:git:https://github.com/otherlodehq/otherlode-agent.git",
                        "scm/developerConnection" to "scm:git:ssh://git@github.com/otherlodehq/otherlode-agent.git",
                    )
                for ((path, expected) in required) {
                    check(field(path) == expected) { "$artifactId POM $path is \"${field(path)}\", expected \"$expected\"" }
                }
                for (path in listOf("name", "description")) {
                    check(!field(path).isNullOrBlank()) { "$artifactId POM has no $path" }
                }
                val dependencies =
                    generateSequence(pom.firstChild) { it.nextSibling }
                        .filterIsInstance<Element>()
                        .firstOrNull { it.tagName == "dependencies" }
                        ?.getElementsByTagName("dependency")
                        ?.let { list ->
                            (0 until list.length).map {
                                val dependency = list.item(it) as Element

                                fun child(tag: String) = dependency.getElementsByTagName(tag).item(0)?.textContent
                                "${child("groupId")}:${child("artifactId")}:${child("version")}:${child("scope")}"
                            }
                        }.orEmpty()
                if (artifactId == "otherlode-agent") {
                    check(
                        generateSequence(pom.firstChild) { it.nextSibling }
                            .filterIsInstance<Element>()
                            .none { it.tagName == "dependencies" },
                    ) { "the agent POM must declare no dependencies, found $dependencies" }
                } else {
                    check(dependencies == listOf("org.jetbrains.kotlin:kotlin-stdlib:2.0.21:runtime")) {
                        "the testkit POM must list only kotlin-stdlib at runtime scope, found $dependencies"
                    }
                }
            }

            val builtJars = mapOf("otherlode-agent" to agentJar.get().asFile, "otherlode-testkit" to testkitJar.get().asFile)
            for ((artifactId, builtJar) in builtJars) {
                val entry =
                    names.single {
                        Regex(
                            "^dev/otherlode/$artifactId/$versionPattern/$artifactId-[^/]+(?<!-sources|-javadoc)\\.jar$",
                        ).matches(it)
                    }
                val publishedEntries = mutableMapOf<String, Long>()
                var manifest = ""
                ZipInputStream(zip.getInputStream(zip.getEntry(entry))).use { stream ->
                    generateSequence { stream.nextEntry }.filterNot { it.isDirectory }.forEach { jarEntry ->
                        val bytes = stream.readAllBytes()
                        publishedEntries[jarEntry.name] = CRC32().also { it.update(bytes) }.value
                        if (jarEntry.name == "META-INF/MANIFEST.MF") manifest = String(bytes)
                    }
                }
                if (artifactId == "otherlode-agent") {
                    check("Premain-Class: dev.otherlode.Agent" in manifest) { "the published agent jar's manifest has no Premain-Class" }
                }
                check(Regex("(?m)^Implementation-Version: ${versionPattern}\\r?$").containsMatchIn(manifest)) {
                    "the published $artifactId jar's manifest does not name version $versionText"
                }
                val built =
                    ZipFile(builtJar).use { jar ->
                        jar
                            .entries()
                            .asSequence()
                            .filterNot { it.isDirectory }
                            .associate { it.name to it.crc }
                    }
                check(built == publishedEntries) {
                    "the published $artifactId jar differs from its shadowJar output: " +
                        "only built ${(built.keys - publishedEntries.keys).take(5)}, " +
                        "only published ${(publishedEntries.keys - built.keys).take(5)}, " +
                        "different ${built.filter { (k, v) -> publishedEntries[k] != null && publishedEntries[k] != v }.keys.take(5)}"
                }

                val module = text(names.single { it.startsWith("dev/otherlode/$artifactId/") && it.endsWith(".module") })
                val variants =
                    Regex(
                        "\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"attributes\"",
                    ).findAll(module).map { it.groupValues[1] }.toList()
                check(variants == listOf("shadowRuntimeElements")) { "$artifactId .module has variants $variants" }
                check(Regex("\"org\\.gradle\\.jvm\\.version\"\\s*:\\s*17\\b").containsMatchIn(module)) {
                    "$artifactId .module does not require JVM 17"
                }
            }
        }
    }
}

// nmcp resolves the aggregation leniently, so a testkit that failed to resolve would drop out of
// the bundle without an error.
tasks.named("nmcpPublishAggregationToCentralPortal") { dependsOn(verifyPublication) }
gradle.taskGraph.whenReady {
    // gradle/publishing.gradle.kts requires signing on the same task path.
    if (hasTask(":nmcpPublishAggregationToCentralPortal")) {
        check(!version.toString().endsWith("-SNAPSHOT")) { "refusing to upload $version: the Portal takes no SNAPSHOT" }
    }
}
