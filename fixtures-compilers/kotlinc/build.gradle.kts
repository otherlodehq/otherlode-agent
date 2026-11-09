// Kotlin fixture classes compiled by each kotlinc release in the compiler matrix (ADR 0055), for
// the generated-method, default-filling, coroutine and lowering rules in BranchSiteAnalyzer. One
// task per release runs that release's own K2JVMCompiler from kotlin-compiler-embeddable against
// the same release's kotlin-stdlib. KGP's Build Tools API was tried first and cannot drive these
// releases from KGP 2.2.21 (kotlin.compiler.runViaBuildToolsApi with kotlin { compilerVersion }
// fails on 1.9.25 and 2.4.20). Only -jvm-target is set, so the language version, API version and
// -jvm-default mode stay each release's own defaults. Never on the test classpath directly: see
// the comment in fixtures-scala3/build.gradle.kts for why.
plugins {
    base
    `java-base`
}

group = "dev.otherlode"

val kotlincVersions = providers.gradleProperty("otherlode.matrix.kotlinc").get().split(",")
val kotlincK1Versions =
    providers
        .gradleProperty("otherlode.matrix.kotlincK1")
        .get()
        .split(",")
        .toSet()

repositories {
    mavenCentral()
}

val toolchains = extensions.getByType<JavaToolchainService>()
val sourceDir = layout.projectDirectory.dir("src/main/kotlin")

val compileTasks =
    kotlincVersions.map { version ->
        val compiler =
            configurations.create("kotlinCompiler$version") {
                isCanBeConsumed = false
            }
        val stdlib =
            configurations.create("kotlinStdlib$version") {
                isCanBeConsumed = false
                isTransitive = false
            }
        dependencies {
            compiler("org.jetbrains.kotlin:kotlin-compiler-embeddable:$version")
            stdlib("org.jetbrains.kotlin:kotlin-stdlib:$version")
        }
        val classesDir = layout.buildDirectory.dir("classes/kotlinc/$version")
        tasks.register<JavaExec>("compileKotlinc$version") {
            group = "build"
            description = "Compiles the fixtures with kotlinc $version."
            inputs.dir(sourceDir).withPropertyName("sources")
            inputs.files(stdlib).withPropertyName("stdlib")
            outputs.dir(classesDir).withPropertyName("classes")
            classpath = compiler
            mainClass.set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
            javaLauncher.set(toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
            val stdlibFiles: FileCollection = stdlib
            val classesPath = classesDir.map { it.asFile.absolutePath }
            val sourcePath = sourceDir.asFile.absolutePath
            argumentProviders.add(
                CommandLineArgumentProvider {
                    listOf(
                        "-no-stdlib",
                        "-no-reflect",
                        "-jvm-target",
                        "21",
                        "-cp",
                        stdlibFiles.singleFile.absolutePath,
                        "-d",
                        classesPath.get(),
                        sourcePath,
                    )
                },
            )
            val outputDir = classesDir.map { it.asFile }
            doFirst { outputDir.get().deleteRecursively() }
        }
    }

// The kotlinx.serialization plugin's output (ADR 0078), compiled apart from the fixtures above: each
// release with its matching compiler plugin and a kotlinx-serialization-core that release can read
// (the newest core metadata a compiler reads is its own plus one). A release from 2.0 to 2.2 also
// compiles them under -language-version 1.9, which makes the frontend K1 and leaves the $serializer
// class non-synthetic; 2.3 and later reject that language version. The K1-only and 2.x-only
// sources join where they compile.
val serializationRuntimes =
    mapOf("1.9.25" to "1.6.3", "2.1.21" to "1.8.1", "2.2.21" to "1.9.0", "2.4.21" to "1.9.0")

// A release the map does not list (the canary adds the newest one) takes the runtime of the newest
// listed release below it, since a compiler reads older core metadata. A plugin that needs a newer
// runtime then fails its own serialization compile, not the whole build's configuration.
fun serializationRuntimeFor(version: String): String {
    serializationRuntimes[version]?.let { return it }
    fun key(release: String) = release.split(".").map(String::toInt)
    val byRelease = Comparator<List<Int>> { a, b -> a.zip(b).map { (x, y) -> x.compareTo(y) }.firstOrNull { it != 0 } ?: a.size.compareTo(b.size) }
    val below =
        serializationRuntimes.keys
            .filter { byRelease.compare(key(it), key(version)) < 0 }
            .maxWithOrNull(compareBy(byRelease, ::key))
    return below?.let { serializationRuntimes.getValue(it) }
        ?: error("kotlinc $version is older than every release in serializationRuntimes; add its kotlinx-serialization-core version.")
}
val serializationRoot = layout.projectDirectory.dir("src/serialization")

val serializationTasks =
    kotlincVersions.flatMap { version ->
        val (major, minor) = version.split(".").map { it.toInt() }
        val isK2Release = major >= 2
        val hasK1Mode = version in kotlincK1Versions
        val plugin =
            configurations.create("serializationPlugin$version") {
                isCanBeConsumed = false
                isTransitive = false
            }
        val classpath =
            configurations.create("serializationClasspath$version") {
                isCanBeConsumed = false
                isTransitive = false
            }
        dependencies {
            plugin("org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:$version")
            classpath("org.jetbrains.kotlin:kotlin-stdlib:$version")
            classpath("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:${serializationRuntimeFor(version)}")
        }
        val compiler = configurations.getByName("kotlinCompiler$version")
        listOf(false, true).filter { !it || hasK1Mode }.map { k1Mode ->
            val key = if (k1Mode) "$version-k1" else version
            val sources =
                listOfNotNull(
                    serializationRoot.dir("common"),
                    serializationRoot.dir("k1").takeIf { k1Mode || !isK2Release },
                    serializationRoot.dir("k2release").takeIf { isK2Release },
                ).map { it.asFile }
            val classesDir = layout.buildDirectory.dir("classes/serialization/$key")
            tasks.register<JavaExec>("compileSerialization$key") {
                group = "build"
                description =
                    "Compiles the serialization fixtures with kotlinc $version${if (k1Mode) " under language version 1.9" else ""}."
                inputs.files(sources.map { fileTree(it) }).withPropertyName("sources")
                inputs.files(plugin).withPropertyName("plugin")
                inputs.files(classpath).withPropertyName("serializationRuntime")
                outputs.dir(classesDir).withPropertyName("classes")
                this.classpath = compiler
                mainClass.set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
                javaLauncher.set(toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
                val pluginFiles: FileCollection = plugin
                val classpathFiles: FileCollection = classpath
                val classesPath = classesDir.map { it.asFile.absolutePath }
                argumentProviders.add(
                    CommandLineArgumentProvider {
                        listOf(
                            "-no-stdlib",
                            "-no-reflect",
                            "-jvm-target",
                            "21",
                            "-module-name",
                            "serialization",
                            "-Xplugin=${pluginFiles.singleFile.absolutePath}",
                            "-cp",
                            classpathFiles.files.joinToString(File.pathSeparator) { it.absolutePath },
                            "-d",
                            classesPath.get(),
                        ) + (if (k1Mode) listOf("-language-version", "1.9") else emptyList()) + sources.map { it.absolutePath }
                    },
                )
                val outputDir = classesDir.map { it.asFile }
                doFirst { outputDir.get().deleteRecursively() }
            }
        }
    }

// A build that mixes a library compiled with -jvm-default=disable and a consumer compiled with
// -jvm-default=all or all-compatibility (ADR 0026). The flag is -Xjvm-default before 2.2, which names
// the modes all and all-compatibility, and -jvm-default from 2.2, whose modes no-compatibility and
// enable are the same two. Each release's classes go to classes/mixed/<release>/{lib,all,all-compatibility}.
val mixedRoot = layout.projectDirectory.dir("src/mixed")

val mixedTasks =
    kotlincVersions.flatMap { version ->
        val (major, minor) = version.split(".").map { it.toInt() }
        val modern = major > 2 || (major == 2 && minor >= 2)
        val compiler = configurations.getByName("kotlinCompiler$version")
        val stdlib = configurations.getByName("kotlinStdlib$version")
        val releaseDir = layout.buildDirectory.dir("classes/mixed/$version")

        fun flag(mode: String): String =
            if (modern) {
                "-jvm-default=" + mapOf("disable" to "disable", "all" to "no-compatibility", "all-compatibility" to "enable").getValue(mode)
            } else {
                "-Xjvm-default=$mode"
            }

        fun register(
            name: String,
            mode: String,
            sources: Directory,
            output: Provider<Directory>,
            libraryDir: Provider<Directory>?,
        ) = tasks.register<JavaExec>("compileMixed$name$version") {
            group = "build"
            description = "Compiles the mixed-mode $name fixtures with kotlinc $version under ${flag(mode)}."
            inputs.dir(sources).withPropertyName("sources")
            inputs.files(stdlib).withPropertyName("stdlib")
            libraryDir?.let { inputs.dir(it).withPropertyName("library") }
            outputs.dir(output).withPropertyName("classes")
            classpath = compiler
            mainClass.set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
            javaLauncher.set(toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
            val stdlibFiles: FileCollection = stdlib
            val outputPath = output.map { it.asFile.absolutePath }
            val libraryPath = libraryDir?.map { it.asFile.absolutePath }
            val sourcePath = sources.asFile.absolutePath
            argumentProviders.add(
                CommandLineArgumentProvider {
                    val libraryClasspath = libraryPath?.let { File.pathSeparator + it.get() }.orEmpty()
                    listOf(
                        "-no-stdlib",
                        "-no-reflect",
                        "-jvm-target",
                        "21",
                        flag(mode),
                        "-cp",
                        stdlibFiles.singleFile.absolutePath + libraryClasspath,
                        "-d",
                        outputPath.get(),
                        sourcePath,
                    )
                },
            )
            val outputDir = output.map { it.asFile }
            doFirst { outputDir.get().deleteRecursively() }
        }

        val library = register("Library", "disable", mixedRoot.dir("lib"), releaseDir.map { it.dir("lib") }, null)
        listOf("all", "all-compatibility").map { mode ->
            register("Consumer${mode.split("-").joinToString("") { it.replaceFirstChar(Char::uppercase) }}", mode, mixedRoot.dir("consumer"), releaseDir.map { it.dir(mode) }, releaseDir.map { it.dir("lib") })
                .also { consumer -> consumer.configure { dependsOn(library) } }
        } + library
    }

tasks.register("classes") {
    dependsOn(compileTasks)
    dependsOn(serializationTasks)
    dependsOn(mixedTasks)
}

tasks.named("assemble") {
    dependsOn(compileTasks)
    dependsOn(serializationTasks)
    dependsOn(mixedTasks)
}
