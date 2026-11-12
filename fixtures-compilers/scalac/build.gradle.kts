// Never on the test classpath directly: see fixtures-scala3/build.gradle.kts for why. A release joins
// scala3-read-releases.txt only after this sweep passes for it. A -Potherlode.matrix.scalac override
// must keep a Scala 2 release: the Scala 2 parameterised tests fail to start when none is given.
import java.io.ByteArrayOutputStream

plugins {
    base
    `java-base`
}

group = "dev.otherlode"

val scalacVersions = providers.gradleProperty("otherlode.matrix.scalac").get().split(",")

repositories {
    mavenCentral()
}

val toolchains = extensions.getByType<JavaToolchainService>()
val scala2Sources = rootProject.layout.projectDirectory.dir("fixtures-scala2/src/main/scala")
val scala3Sources = rootProject.layout.projectDirectory.dir("fixtures-scala3/src/main/scala")

val compileTasks =
    scalacVersions.map { version ->
        val scala3 = version.startsWith("3.")
        val compiler =
            configurations.create("scalaCompiler$version") {
                isCanBeConsumed = false
            }
        val library =
            configurations.create("scalaLibrary$version") {
                isCanBeConsumed = false
            }
        dependencies {
            if (scala3) {
                compiler("org.scala-lang:scala3-compiler_3:$version")
                library("org.scala-lang:scala3-library_3:$version")
            } else {
                compiler("org.scala-lang:scala-compiler:$version")
                library("org.scala-lang:scala-library:$version")
            }
        }
        val mainClassName = if (scala3) "dotty.tools.dotc.Main" else "scala.tools.nsc.Main"
        val sourceDir = if (scala3) scala3Sources else scala2Sources
        val sourceFiles = fileTree(sourceDir) { include("**/*.scala") }
        val classesDir = layout.buildDirectory.dir("classes/scalac/$version")
        val versionFile = layout.buildDirectory.file("scalac-version/$version.txt")
        val launcher = toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }

        val reportVersion =
            tasks.register<JavaExec>("versionScalac$version") {
                group = "build"
                description = "Records what scalac $version prints for -version."
                inputs.files(compiler).withPropertyName("compiler")
                outputs.file(versionFile).withPropertyName("versionFile")
                classpath = compiler
                mainClass.set(mainClassName)
                javaLauncher.set(launcher)
                args("-version")
                val target = versionFile.map { it.asFile }
                val output = ByteArrayOutputStream()
                standardOutput = output
                errorOutput = output
                doLast {
                    val file = target.get()
                    file.parentFile.mkdirs()
                    file.writeBytes(output.toByteArray())
                }
            }

        tasks.register<JavaExec>("compileScalac$version") {
            group = "build"
            description = "Compiles the fixtures with scalac $version."
            dependsOn(reportVersion)
            inputs.files(sourceFiles).withPropertyName("sources").withPathSensitivity(PathSensitivity.RELATIVE)
            inputs.files(library).withPropertyName("library")
            outputs.dir(classesDir).withPropertyName("classes")
            classpath = compiler
            mainClass.set(mainClassName)
            javaLauncher.set(launcher)
            val libraryFiles: FileCollection = library
            val classesPath = classesDir.map { it.asFile.absolutePath }
            val sourcePaths = provider { sourceFiles.files.map { it.absolutePath }.sorted() }
            argumentProviders.add(
                CommandLineArgumentProvider {
                    listOf(
                        "-release",
                        "21",
                        "-classpath",
                        libraryFiles.asPath,
                        "-d",
                        classesPath.get(),
                    ) + sourcePaths.get()
                },
            )
            val outputDir = classesDir.map { it.asFile }
            doFirst {
                outputDir.get().apply {
                    deleteRecursively()
                    mkdirs()
                }
            }
        }
    }

tasks.register("classes") {
    dependsOn(compileTasks)
}

tasks.named("assemble") {
    dependsOn(compileTasks)
}
