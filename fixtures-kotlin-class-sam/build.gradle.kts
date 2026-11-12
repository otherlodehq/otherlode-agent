plugins {
    kotlin("jvm")
}

group = "dev.otherlode"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll("-Xsam-conversions=class", "-Xlambdas=class")
    }
}
