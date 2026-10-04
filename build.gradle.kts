import java.nio.ByteBuffer
import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.2.21"
    id("com.gradleup.shadow") version "8.3.11"
    id("me.champeau.jmh") version "0.7.3"
}

group = "dev.otherlode"

repositories {
    mavenCentral()
}

// `-Potherlode.testJdk=25` runs every project's tests on that JDK. Compilation keeps the JDK 21
// toolchain; only the JVM that runs the tests changes. CI sets it so a JDK that renames an
// internal the agent reads, such as the lambda factory's `interfaceClass` and `implInfo` fields,
// fails a test.
val testJdk = providers.gradleProperty("otherlode.testJdk")

// One JaCoCo version for its core library and its agent jar: CoverageAgentOrderTest reads the
// agent's execution data with the core's reader and compares ids with the core's CRC64.
val jacocoVersion = "0.8.13"
allprojects {
    plugins.withType<JavaBasePlugin> {
        val toolchains = extensions.getByType<JavaToolchainService>()
        tasks.withType<Test>().configureEach {
            if (testJdk.isPresent) {
                javaLauncher.set(toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(testJdk.get())) })
            }
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
evaluationDependsOn(":demo-spring")
evaluationDependsOn(":endpoints-ktor-3")

dependencies {
    // Compile-time only: at runtime the holder class comes from the target JVM's bootstrap
    // classloader, where BootstrapHolder appends the embedded jar below. Shipping it as loose
    // classes in this jar too would put a second copy on the system loader.
    compileOnly(project(":bootstrap"))

    // testCompileOnly does not extend compileOnly by default, so tests that reference a
    // bootstrap-resident type directly (loaded for real via BootstrapHolder.install) need the
    // same compile-time-only dependency repeated here.
    testCompileOnly(project(":bootstrap"))

    // Main artifact ships ASM shaded under net.bytebuddy.jar.asm.*, which the
    // branch-tracking tier's AsmVisitorWrapper uses directly instead of pulling
    // in a second, independently-versioned ASM dependency.
    implementation("net.bytebuddy:byte-buddy:1.18.12")

    // ASM's JSRInlinerAdapter, relocated into Byte Buddy's ASM package, which bundles the core
    // classes but not this one. The branch tier computes frames, which cannot handle the
    // jsr/ret of a class file below version 50, so such a class is inlined first.
    implementation(files(asmSubroutinesJar))

    // EndpointModule, AdviceBinder, and the ByteBuddy-facing types a per-framework endpoint
    // module is written against. A per-framework subproject depends on this module and never on
    // this one, the root project, since the root project depends on the per-framework modules to
    // merge their advice into the shaded jar: depending the other way would be a cycle.
    implementation(project(":endpoints-api"))

    // The first real endpoint module: the JDK's own com.sun.net.httpserver.HttpServer.
    implementation(project(":endpoints-jdk-httpserver"))

    // Endpoint module for Spring MVC, covering Spring Framework 5.3, 6.x and 7.x with one module.
    implementation(project(":endpoints-spring-webmvc"))

    // Endpoint modules for Ktor. Route became the interface RoutingNode between 2.x and 3.x, so
    // the two major versions need their own module rather than one shared one.
    implementation(project(":endpoints-ktor-2"))
    implementation(project(":endpoints-ktor-3"))

    // Endpoint module for JAX-RS, covering both the javax.ws.rs and jakarta.ws.rs namespaces with
    // one module. JAX-RS has no registration hook to advise, so its declared list comes from
    // reading annotations at transform time instead; see the module's own KDoc.
    implementation(project(":endpoints-jaxrs"))

    // Route bridge endpoint module: counts the route OpenTelemetry's own HTTP server
    // instrumentation resolved, for a framework none of the modules above cover. Off by default
    // (AgentConfig.otelBridgeEnabled).
    implementation(project(":endpoints-otel-bridge"))

    // The payload models, the codec and the wire schema they encode
    // (src/main/proto/otherlode/v1/otherlode.proto), shared with the testkit. Every type the agent
    // takes from it is part of this project's own API, and protobuf-java comes with it; both are
    // relocated in the shaded jar below.
    api(project(":wire"))

    testImplementation(kotlin("test"))

    // The compiler matrix tests run once per kotlinc release and javac version. The version is the
    // one kotlin("test") already brings in for junit-jupiter-api.
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.1")
    testImplementation("net.bytebuddy:byte-buddy-agent:1.18.12")

    // Drives javassist's own proxy generator, so the name rule for its classes is tested against
    // a class it really defined.
    testImplementation("org.javassist:javassist:3.30.2-GA")

    // JaCoCo's offline instrumenter, used only to produce the bytecode shape a coverage agent
    // attached ahead of this one hands to the transformer chain, so the analyser is tested
    // against the real thing rather than a hand-written imitation of it.
    testImplementation("org.jacoco:org.jacoco.core:$jacocoVersion")
}

kotlin {
    jvmToolchain(21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// The bootstrap module's jar rides inside this one as a plain resource. premain writes it to a
// temp file and hands it to Instrumentation.appendToBootstrapClassLoaderSearch, which takes a jar
// on disk and nothing else. Embedding the finished jar rather than its classes is what keeps the
// holder out of this jar's own class tree, so the shadow relocation below never touches it and
// the system loader never sees a second copy.
//
// The resource deliberately does not end in ".jar": shadowJar explodes every file with that
// extension it copies, dependency or not, which would scatter the holder's classes into this
// jar as loose files and drop the resource itself. The demo would not have caught that, since
// its classpath also carries the plain jar where the resource survives; verifyAgentJar below
// checks the shaded jar directly.
val bootstrapResourcePath = "META-INF/otherlode/bootstrap-jar.bin"

val embedBootstrapJar by tasks.registering(Sync::class) {
    from(project(":bootstrap").tasks.named<Jar>("jar")) {
        into(bootstrapResourcePath.substringBeforeLast('/'))
        rename { bootstrapResourcePath.substringAfterLast('/') }
    }
    // This directory becomes a resource root, so the META-INF/otherlode prefix above is what the
    // resource path inside the agent jar ends up being.
    into(layout.buildDirectory.dir("generated-resources/bootstrap"))
}

sourceSets.main {
    resources.srcDir(embedBootstrapJar)
}

// Licence texts for the dependencies relocated into the shaded jar, one directory per
// component. Byte Buddy is the only one of them that ships its own LICENSE and NOTICE entries;
// the rest carry nothing, so the texts are checked in here rather than extracted at build time.
val bundledLicensesDir = layout.projectDirectory.dir("licenses")
val bundledLicenseEntries =
    fileTree(bundledLicensesDir).files.map { it.relativeTo(bundledLicensesDir.asFile).invariantSeparatorsPath }.sorted()

// Fails the build if the shaded jar does not have the shape premain relies on: the embedded
// holder jar present as one resource, and none of the holder's classes present loose. Also
// checks that META-INF/NOTICE is this project's own and that every vendored third-party licence
// made it under META-INF/licenses/.
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
            // The subroutine inliner reaches the jar from :asm-subroutines, relocated twice; without it every
            // class below version 51 with a subroutine fails to weave.
            val inliner = "dev/otherlode/shaded/bytebuddy/jar/asm/commons/JSRInlinerAdapter.class"
            check(inliner in names) { "agent jar is missing ASM's subroutine inliner at $inliner" }
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

            // Endpoint advice classes are inlined into framework bytecode by ByteBuddy, never
            // loaded as ordinary agent classes. Only the annotated method's own bytecode is
            // copied; anything else the advice touches stays a reference the target's own
            // classloader has to resolve, and from a bootstrap-loaded target, or an isolating
            // container loader, that resolution fails with NoClassDefFoundError and the module
            // disables itself at the first request. Each class under this package must therefore
            // reference no agent class except itself and the bootstrap seam (with its nested
            // classes, which load from the same bootstrap jar), call no method on
            // itself (a private helper is a real call at the woven site), carry no synthetic
            // member (a lambda, a switch over an enum, or an assert compiles to one), and never
            // mention the relocated Kotlin stdlib. Passes trivially while the package is empty.
            val adviceClassNames = names.filter { it.startsWith("dev/otherlode/endpoints/") && it.endsWith(".class") }
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
                                it != "dev/otherlode/bootstrap/OtherlodeEndpoints" &&
                                !it.startsWith("dev/otherlode/bootstrap/OtherlodeEndpoints$") &&
                                !it.startsWith("dev/otherlode/shaded/bytebuddy/")
                        }
                    if (foreignAgentClasses.isNotEmpty()) problems += "references agent classes $foreignAgentClasses"
                    if (shape.selfMethodCalls.isNotEmpty()) problems += "calls its own methods ${shape.selfMethodCalls}"
                    if (shape.syntheticMembers.isNotEmpty()) problems += "declares synthetic members ${shape.syntheticMembers}"
                    problems.map { "${shape.name}: $it" }
                }
            check(adviceProblems.isEmpty()) {
                "endpoint advice classes must be self-contained (see the comment in verifyAgentJar):\n  " +
                    adviceProblems.joinToString("\n  ")
            }
        }
    }
}

/** The parts of a class file the advice check reads; see [parseClassShape]. */
class ClassShape(
    val name: String,
    val utf8Constants: List<String>,
    val referencedClasses: Set<String>,
    val selfMethodCalls: Set<String>,
    val syntheticMembers: List<String>,
)

/**
 * Reads a class file's constant pool and member tables, enough to know which classes it
 * references, which of its own methods it calls, and whether it declares a synthetic member.
 * Written here rather than through ASM because the build script has no bytecode library on its
 * classpath, and the class-file format's constant pool is a short, fixed set of tagged entries.
 */
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

            8, 16, 19, 20 -> {
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
    return ClassShape(name, utf8.filterNotNull(), referencedClasses, selfMethodCalls, synthetic)
}

tasks.shadowJar {
    finalizedBy(verifyAgentJar)
}

// Scala default-getter resolution is proven against real scalac output, compiled by the two
// Scala fixture modules below, `$DefaultImpls` marking against kotlinc output under
// -jvm-default=disable, compiled by the third, the handler forwarder table against kotlinc
// output under class-based SAM conversion, compiled by the fourth, and the generated-method and
// lowering rules against every kotlinc and javac in the compiler matrix. None is a
// test dependency, only a task dependency: putting one on the test classpath would let JUnit
// discovery load these classes before install() wires up instrumentation, defeating the
// fixture's purpose (see FixtureClassLoader). The output directory and runtime classpath are
// read lazily through providers, resolved only when the test task actually runs, so configuring
// this project never forces a fixture project to evaluate its dependencies.
val scala3FixtureClassesDir = project(":fixtures-scala3").layout.buildDirectory.dir("classes/scala/main")
val scala3FixtureRuntimeClasspath = project(":fixtures-scala3").configurations.named("runtimeClasspath")
val scala2FixtureClassesDir = project(":fixtures-scala2").layout.buildDirectory.dir("classes/scala/main")
val scala2FixtureRuntimeClasspath = project(":fixtures-scala2").configurations.named("runtimeClasspath")
val jvmDefaultDisableFixtureClassesDir =
    project(":fixtures-kotlin-jvm-default-disable").layout.buildDirectory.dir("classes/kotlin/main")
val jvmDefaultDisableFixtureRuntimeClasspath =
    project(":fixtures-kotlin-jvm-default-disable").configurations.named("runtimeClasspath")
val classSamFixtureClassesDir = project(":fixtures-kotlin-class-sam").layout.buildDirectory.dir("classes/kotlin/main")

// The probe-window fixtures (ADR 0060), compiled once at class-file version 52 and once at the
// toolchain's own: each form's Java and Kotlin class directories, and the Kotlin stdlib the Kotlin
// ones link against.
val probeWindowProject = project(":fixtures-probe-window")
val probeWindowForms = listOf("legacy", "modern")
val probeWindowClassesDirs =
    probeWindowForms.associateWith { form ->
        listOf("java", "kotlin").map { probeWindowProject.layout.buildDirectory.dir("classes/$it/$form") }
    }
val probeWindowKotlinStdlib = probeWindowProject.configurations.named("kotlinStdlib")

// The compiler matrix (ADR 0055): one fixture build per kotlinc release and per javac version, each
// compiled by that compiler (see fixtures-compilers). Same arrangement as above, a task dependency
// and a system property per build holding its class directory, named by the release.
val kotlincMatrix = providers.gradleProperty("otherlode.matrix.kotlinc").get().split(",")
val javacMatrix = providers.gradleProperty("otherlode.matrix.javac").get().split(",")
val kotlincSuspendsSource =
    project(":fixtures-kotlinc").file("src/main/kotlin/com/example/target/kotlinc/Suspends.kt")
val kotlincMatrixClassesDirs =
    kotlincMatrix.associateWith { project(":fixtures-kotlinc").layout.buildDirectory.dir("classes/kotlinc/$it") }
val javacMatrixClassesDirs =
    javacMatrix.associateWith { project(":fixtures-javac").layout.buildDirectory.dir("classes/javac/$it") }
val scalacMatrix = providers.gradleProperty("otherlode.matrix.scalac").get().split(",")
val scalacMatrixClassesDirs =
    scalacMatrix.associateWith { project(":fixtures-scalac").layout.buildDirectory.dir("classes/scalac/$it") }
val scalacMatrixVersionFiles =
    scalacMatrix.associateWith { project(":fixtures-scalac").layout.buildDirectory.file("scalac-version/$it.txt") }

// JaCoCo's own agent jar, for CoverageAgentOrderTest, which launches JVMs with it beside this
// agent in each command-line order. The test needs only the jar's path, so it stays off the test
// classpath. The name stays clear of `jacocoAgent`, which the `jacoco` plugin creates when an init
// script applies it.
val coverageAgentOrderJacoco by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    coverageAgentOrderJacoco("org.jacoco:org.jacoco.agent:$jacocoVersion:runtime")
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
    )
    // A fixture build is not on the test classpath, so its classes are declared as inputs here:
    // otherwise a fixture change would recompile the fixture and leave the test task up to date.
    listOf(
        "scala3" to scala3FixtureClassesDir,
        "scala2" to scala2FixtureClassesDir,
        "jvmDefaultDisable" to jvmDefaultDisableFixtureClassesDir,
        "classSam" to classSamFixtureClassesDir,
    ).plus(kotlincMatrixClassesDirs.map { (version, dir) -> "kotlinc$version" to dir })
        .plus(javacMatrixClassesDirs.map { (version, dir) -> "javac$version" to dir })
        .plus(scalacMatrixClassesDirs.map { (version, dir) -> "scalac$version" to dir })
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
    // KotlincMatrixTest reads the source's marker comments for line numbers, which the class files
    // alone do not reflect.
    inputs.file(kotlincSuspendsSource).withPropertyName("kotlincSuspendsSource").withPathSensitivity(PathSensitivity.NONE)
    // ShadedBodyKindRuleTest runs the analyser from the shaded jar, since relocation rewrites
    // string constants in the agent's own classes and only the shaded copy shows the effect.
    // CoverageAgentOrderTest launches JVMs with the shaded jar as their -javaagent.
    dependsOn(tasks.shadowJar)
    val shadedAgentJar = tasks.shadowJar.flatMap { it.archiveFile }
    inputs.file(shadedAgentJar)
    inputs.files(coverageAgentOrderJacoco).withPropertyName("coverageAgentOrderJacoco")
    doFirst {
        systemProperty("otherlode.agent.shadedJar", shadedAgentJar.get().asFile.absolutePath)
        systemProperty("otherlode.agent.version", project.version.toString())
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
        systemProperty("otherlode.fixtures.kotlinc.suspendsSource", kotlincSuspendsSource.absolutePath)
        kotlincMatrixClassesDirs.forEach { (version, dir) ->
            systemProperty("otherlode.fixtures.kotlinc.$version.dir", dir.get().asFile.absolutePath)
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

// The transform-time benchmark's corpora: class sets by corpus name, each one module's output or
// one jar. BenchmarkCorpus reads them from the system properties below, in the JMH fork and in the
// test that keeps them loadable. spring-webmvc is the jar demo-spring already resolves, a large
// body of real Java. ktor-server-core is the jar endpoints-ktor-3 tests against, a large body of
// real Kotlin with suspend functions and inline functions. The agent's own classes are not a
// corpus: TypeMatchPolicy never includes the agent's package, so the analyser would drop their
// inlined copies and call edges, which no adopter class sees.
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

// The classpath that resolves each class set's own dependencies when the code-size test weaves it,
// keyed `<corpus>.<class set>` as the corpus properties are. The test reads the class files from the
// corpus and defines nothing from it, so the loader only has to answer the transformer's type lookups.
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

// ProbeAllocationTest runs in its own JVM with escape analysis off: C2 would otherwise remove a
// boxed value or a varargs array that stays inside the call, so a probe that made one would read as
// allocating nothing, though it allocates in a larger method or before C2 compiles it. Run it with
// `./gradlew probeAllocationTest`; `test` leaves it out.
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

// The hot-path suite weaves fixtures through the real transformer, which self-attaches, and reaches
// the bootstrap seam classes the same way the test source set does.
dependencies {
    "jmhCompileOnly"(project(":bootstrap"))
    "jmhImplementation"("net.bytebuddy:byte-buddy-agent:1.18.12")
}

// Fork, warmup and measurement settings live on the benchmark classes. `-Potherlode.benchmark.corpus=demo`
// runs one corpus of the analyser benchmark; a comma-separated list runs several.
// `-Potherlode.benchmark.include=HotPathBenchmark` runs only the benchmarks whose names match that
// regular expression. Every run adds JMH's GC profiler, so each score carries its allocation per operation.
jmh {
    jmhVersion.set("1.37")
    includeTests.set(false)
    profilers.add("gc")
    jvmArgsAppend.add("-Djdk.attach.allowAttachSelf=true")
    providers.gradleProperty("otherlode.benchmark.include").orNull?.let { includes.add(it) }
    jvmArgsAppend.addAll(provider { benchmarkCorpusProperties().map { (key, value) -> "-D$key=$value" } })
    providers.gradleProperty("otherlode.benchmark.corpus").orNull?.let { selected ->
        benchmarkParameters.put("corpus", objects.listProperty<String>().value(selected.split(',').map { it.trim() }))
    }
}

tasks.named("jmh") {
    inputs.files(benchmarkCorpusFiles).withPropertyName("benchmarkCorpora")
}

// BenchmarkCorpusTest compiles against the benchmark's own corpus loader. So every build also
// compiles the benchmark, and an analyser change that breaks it fails the build.
sourceSets.test {
    compileClasspath += sourceSets["jmh"].output
    runtimeClasspath += sourceSets["jmh"].output
}

val agentMainClass = "dev.otherlode.Agent"

// The plain jar task would otherwise write to the same build/libs/otherlode-agent-*.jar
// path as shadowJar below (its classifier is cleared to make that the single
// distributable file), and whichever task happened to run last would win,
// silently overwriting the shaded agent jar with one missing the
// Premain-Class manifest attribute and the relocated dependencies. Giving the
// plain jar its own classifier keeps the two outputs apart without disabling
// the task outright: disabling it breaks `project(":")` consumers
// (the demo module's compile classpath), which resolve a local project
// dependency's default `apiElements`/`runtimeElements` variant back to this
// task's output. Only shadowJar's output is ever meant to be distributed or
// used as the -javaagent jar; the plain jar exists solely so in-repo project
// dependencies keep working.
tasks.jar {
    archiveClassifier.set("plain")
}

tasks.shadowJar {
    archiveClassifier.set("")

    // Each per-framework endpoint module subproject ships its own
    // META-INF/services/dev.otherlode.instrumentation.endpoints.api.EndpointModule
    // entry. Without this, shadow keeps only one such file (whichever dependency it copies
    // last), so every module but one would silently vanish from ServiceLoader discovery.
    mergeServiceFiles()

    // Relocate ByteBuddy so it can't collide with a possibly
    // differently-versioned copy already on the target application's classpath.
    relocate("net.bytebuddy", "dev.otherlode.shaded.bytebuddy")

    // Same rationale for protobuf-java: the target app may already carry its
    // own, differently-versioned copy on the classpath.
    relocate("com.google.protobuf", "dev.otherlode.shaded.protobuf")

    // The wire module's classes, and the root's own in the same packages. A test classpath that
    // carries the testkit carries the wire module too, possibly at another version, and the agent
    // jar is appended behind it: unrelocated, the agent would link against that copy instead of
    // its own.
    relocate("dev.otherlode.export", "dev.otherlode.shaded.export")
    relocate("dev.otherlode.proto", "dev.otherlode.shaded.proto")
    relocate("dev.otherlode.registry.RouteTemplateNormalizer", "dev.otherlode.shaded.registry.RouteTemplateNormalizer")

    // Most of the agent itself is Kotlin, so kotlin-stdlib is unavoidably on this jar's own
    // classpath too. Left unrelocated, it collides exactly the same way ByteBuddy and protobuf
    // would: a Kotlin target app almost certainly carries its own, possibly differently-versioned
    // copy of the same classes on the system classloader the agent shares with it.
    relocate("kotlin", "dev.otherlode.shaded.kotlin")

    // Transitive dependency of kotlin-stdlib (org.jetbrains:annotations). Same collision
    // rationale, lower stakes since these are stable marker annotations, but no reason to leave
    // them unshaded either.
    relocate("org.jetbrains.annotations", "dev.otherlode.shaded.annotations")
    relocate("org.intellij.lang.annotations", "dev.otherlode.shaded.intellij.annotations")

    // META-INF/LICENSE and META-INF/NOTICE must describe this jar, not whichever dependency's
    // entries shadow happened to copy first, so every dependency's own copies are dropped and
    // the vendored texts under licenses/ go in under META-INF/licenses/ instead. The exclude
    // patterns match a dependency entry's path inside its jar; the from() blocks below are
    // matched against their own source paths (LICENSE, byte-buddy/NOTICE), which the patterns
    // do not cover, so they land where into() sends them.
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
