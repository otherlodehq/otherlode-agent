// Never on the test classpath directly: see fixtures-scala3/build.gradle.kts for why.
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
