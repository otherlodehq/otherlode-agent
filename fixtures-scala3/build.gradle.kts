// Never on the test classpath directly: JUnit discovery would load these classes before
// instrumentation is installed.
plugins {
    scala
}

group = "dev.otherlode"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.scala-lang:scala3-library_3:3.3.4")
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
