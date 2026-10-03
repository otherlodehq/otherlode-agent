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

tasks.register("classes") {
    dependsOn(compileTasks)
}

tasks.named("assemble") {
    dependsOn(compileTasks)
}
