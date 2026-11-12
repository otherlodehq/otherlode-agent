// This module loads on the bootstrap classloader, so it stays Java-only with no dependencies. Anything
// else compiles but fails at runtime inside a customer JVM with NoClassDefFoundError.
plugins {
    java
}

group = "dev.otherlode"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.jar {
    archiveBaseName.set("otherlode-bootstrap")
}
