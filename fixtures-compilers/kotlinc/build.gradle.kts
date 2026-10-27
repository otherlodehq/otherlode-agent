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
    mapOf("1.9.25" to "1.6.3", "2.1.21" to "1.8.1", "2.2.21" to "1.9.0", "2.4.20" to "1.9.0")
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
            classpath("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:${serializationRuntimes.getValue(version)}")
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

tasks.register("classes") {
    dependsOn(compileTasks)
    dependsOn(serializationTasks)
}

tasks.named("assemble") {
    dependsOn(compileTasks)
    dependsOn(serializationTasks)
}
