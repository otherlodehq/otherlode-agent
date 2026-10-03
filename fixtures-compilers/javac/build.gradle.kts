// Java fixture classes compiled with each javac version in the compiler matrix (ADR 0055), each through a toolchain of that
// version at its own default target, for the record and switch rules in BranchSiteAnalyzer. The
// sources under src/java21 use pattern switches and are compiled by 21 and later only. Never on the
// test classpath directly: see the comment in fixtures-scala3/build.gradle.kts for why.
plugins {
    java
}

group = "dev.otherlode"

val javacVersions =
    providers
        .gradleProperty("otherlode.matrix.javac")
        .get()
        .split(",")
        .map { it.toInt() }

val toolchains = extensions.getByType<JavaToolchainService>()

javacVersions.forEach { version ->
    val sources =
        fileTree("src/common") +
            if (version >= 21) fileTree("src/java21") else files()
    tasks.register<JavaCompile>("compileJavac$version") {
        group = "build"
        description = "Compiles the fixtures with javac $version."
        source = sources.asFileTree
        classpath = files()
        destinationDirectory.set(layout.buildDirectory.dir("classes/javac/$version"))
        javaCompiler.set(toolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(version)) })
    }
}

tasks.named("classes") {
    dependsOn(javacVersions.map { "compileJavac$it" })
}
