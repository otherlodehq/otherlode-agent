// Scala fixture classes compiled by each scalac release in the compiler matrix (ADR 0055), for the
// generated-method rules in ScalaGeneratedMethods. The sources are the ones the baseline modules
// compile, fixtures-scala2 (2.13.15) for the 2.x releases and fixtures-scala3 (3.3.4) for the 3.x
// ones, read in place and never copied. One task per release runs that release's own compiler
// (scala.tools.nsc.Main from scala-compiler, dotty.tools.dotc.Main from scala3-compiler_3) with its
// own library on the compile classpath, and a second task per release records what that compiler
// prints for -version, so the build shows which compiler ran. Never on the test classpath
// directly: see the comment in fixtures-scala3/build.gradle.kts for why.
//
// The releases come from otherlode.matrix.scalac in gradle.properties, which -P overrides. A release
// joins src/main/resources/dev/otherlode/scala3-read-releases.txt only after this sweep passes for
// it, so the sweep over every Scala 3 release in that file is
//   ./gradlew :test --tests '*ScalacMatrixTest*' -Potherlode.matrix.scalac=<every Scala 3 release in scala3-read-releases.txt>
// with the Scala 2 releases of gradle.properties kept in the list: the Scala 2 parameterised tests
// fail to start when no Scala 2 release is given.
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
