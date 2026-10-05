// Scala 2.13 fixture classes for the analyser's default-getter resolution
// (BranchSiteAnalyzer). Never on the test classpath directly: see the
// comment in fixtures-scala3/build.gradle.kts for why.
plugins {
    scala
}

group = "dev.otherlode"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.scala-lang:scala-library:2.13.15")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// The Scala plugin targets the toolchain's own class-file version; the fixtures must load on the
// oldest test JVM.
tasks.withType<ScalaCompile>().configureEach {
    scalaCompileOptions.additionalParameters = listOf("-release", "17")
}
