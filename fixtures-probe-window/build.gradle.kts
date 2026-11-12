// Never on the test classpath directly: see fixtures-scala3/build.gradle.kts for why.
plugins {
    base
    `java-base`
}

group = "dev.otherlode"

repositories {
    mavenCentral()
}

val kotlinVersion = "2.2.21"
val toolchains = extensions.getByType<JavaToolchainService>()
val kotlinSourceDir = layout.projectDirectory.dir("src/main/kotlin")

val kotlinCompiler by configurations.creating { isCanBeConsumed = false }
val kotlinStdlib by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    kotlinCompiler("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinVersion")
    kotlinStdlib("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
}

val forms = listOf(Triple("legacy", 8, "1.8"), Triple("modern", 17, "17"))

val compileTasks =
    forms.flatMap { (form, release, jvmTarget) ->
        val javaTask =
            tasks.register<JavaCompile>("compileJava${form.replaceFirstChar { it.uppercase() }}") {
                group = "build"
                description = "Compiles the Java fixtures at release $release."
                source = fileTree("src/main/java")
                classpath = files()
                destinationDirectory.set(layout.buildDirectory.dir("classes/java/$form"))
                options.release.set(release)
                options.compilerArgs.add("-Xlint:-options")
                javaCompiler.set(toolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(21)) })
            }
        val classesDir = layout.buildDirectory.dir("classes/kotlin/$form")
        val kotlinTask =
            tasks.register<JavaExec>("compileKotlin${form.replaceFirstChar { it.uppercase() }}") {
                group = "build"
                description = "Compiles the Kotlin fixtures with -jvm-target $jvmTarget."
                inputs.dir(kotlinSourceDir).withPropertyName("sources")
                inputs.files(kotlinStdlib).withPropertyName("stdlib")
                outputs.dir(classesDir).withPropertyName("classes")
                classpath = kotlinCompiler
                mainClass.set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
                javaLauncher.set(toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
                val stdlibFiles: FileCollection = kotlinStdlib
                val classesPath = classesDir.map { it.asFile.absolutePath }
                val sourcePath = kotlinSourceDir.asFile.absolutePath
                argumentProviders.add(
                    CommandLineArgumentProvider {
                        listOf(
                            "-no-stdlib",
                            "-no-reflect",
                            "-jvm-target",
                            jvmTarget,
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
        listOf(javaTask, kotlinTask)
    }

tasks.register("classes") {
    dependsOn(compileTasks)
}

tasks.named("assemble") {
    dependsOn(compileTasks)
}
