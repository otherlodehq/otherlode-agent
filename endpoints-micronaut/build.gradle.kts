import org.gradle.api.attributes.java.TargetJvmVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.tasks.UsesKotlinJavaToolchain

plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("kapt")
}

group = "dev.otherlode"

repositories {
    mavenCentral()
}

val kotlinVersion = "2.2.21"
val byteBuddyVersion = "1.18.12"
val slf4jVersion = "2.0.16"
val coroutinesVersion = "1.9.0"

val micronaut4Version = providers.gradleProperty("otherlode.framework.micronaut4").getOrElse("4.10.32")
val micronaut5Version = providers.gradleProperty("otherlode.framework.micronaut5").getOrElse("5.2.16")

val micronaut3 =
    configurations.create("micronaut3") {
        isCanBeConsumed = false
        isTransitive = false
    }

dependencies {
    implementation(project(":endpoints-api"))

    compileOnly(project(":bootstrap"))
    compileOnly("net.bytebuddy:byte-buddy:$byteBuddyVersion")

    // Compiled against the oldest supported release, per ADR 0017. Never `implementation`: Micronaut
    // belongs to the target application and must not be shaded into the agent jar.
    compileOnly("io.micronaut:micronaut-router:4.0.0")
    compileOnly("io.micronaut:micronaut-http-server:4.0.0")

    // Micronaut 3's router and server jars, read as class files by the matcher test, never loaded.
    micronaut3("io.micronaut:micronaut-router:3.10.15")
    micronaut3("io.micronaut:micronaut-http-server:3.10.15")
}

kotlin {
    jvmToolchain(21)
}

val java25Launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
val java25Compiler = javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(25)) }

// One test source set per Micronaut release under test. The controllers need Micronaut's annotation
// processor at the release they run on, so each set compiles the shared sources again with its own.
// `test` runs the newest 4.x, micronaut40Test the 4.0.0 floor and micronaut5Test the newest 5.x, which
// needs class-file version 69 and so JDK 25 to compile and to run.
fun registerMicronautTests(
    setName: String,
    micronautVersion: String,
    onJava25: Boolean,
) {
    val sourceSet =
        if (setName == "test") {
            sourceSets.getByName("test")
        } else {
            sourceSets.create(setName) {
                java.srcDir("src/test/java")
                kotlin.srcDir("src/test/kotlin")
                compileClasspath += sourceSets.main.get().output
                runtimeClasspath += sourceSets.main.get().output
            }
        }
    val prefix = if (setName == "test") "test" else setName
    val implementation = configurations.getByName("${prefix}Implementation")
    configurations.getByName("${prefix}RuntimeOnly")
    if (setName != "test") {
        implementation.extendsFrom(configurations.getByName("implementation"))
    }
    dependencies {
        add(implementation.name, project(":endpoints-api"))
        add(implementation.name, project(":"))
        add(implementation.name, "org.jetbrains.kotlin:kotlin-test-junit5:$kotlinVersion")
        add(implementation.name, "net.bytebuddy:byte-buddy-agent:$byteBuddyVersion")
        add(implementation.name, "net.bytebuddy:byte-buddy:$byteBuddyVersion")
        add(implementation.name, "io.micronaut:micronaut-http-server-netty:$micronautVersion")
        add(implementation.name, "io.micronaut:micronaut-runtime:$micronautVersion")
        add(implementation.name, "io.micronaut:micronaut-websocket:$micronautVersion")
        add(implementation.name, "io.micronaut:micronaut-management:$micronautVersion")
        add(implementation.name, "io.micronaut:micronaut-jackson-databind:$micronautVersion")
        add(implementation.name, "org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
        add(implementation.name, "org.jetbrains.kotlinx:kotlinx-coroutines-reactor:$coroutinesVersion")
        add(implementation.name, "org.slf4j:slf4j-nop:$slf4jVersion")
        val kaptName = if (setName == "test") "kaptTest" else "kapt${setName.replaceFirstChar { it.uppercase() }}"
        add(kaptName, "io.micronaut:micronaut-inject-java:$micronautVersion")
    }
    val testTask =
        if (setName == "test") {
            tasks.named<Test>("test")
        } else {
            tasks.register<Test>(setName) {
                description = "Runs the tests against Micronaut $micronautVersion."
                group = "verification"
                testClassesDirs = sourceSet.output.classesDirs
                classpath = sourceSet.runtimeClasspath
            }
        }
    testTask.configure {
        useJUnitPlatform()
        jvmArgs("-Djdk.attach.allowAttachSelf=true")
        inputs.files(micronaut3).withPropertyName("micronaut3Jars")
        doFirst { systemProperty("otherlode.micronaut3.jars", micronaut3.asPath) }
        if (onJava25) javaLauncher.set(java25Launcher)
    }
    if (onJava25) {
        tasks.named<JavaCompile>(sourceSet.compileJavaTaskName) { javaCompiler.set(java25Compiler) }
    }
    tasks.check { dependsOn(testTask) }
}

// Micronaut 5 publishes for JVM 25, and the root build compiles this module for 17. The suite that
// needs it resolves for 25 and compiles and runs on JDK 25.
tasks.withType<KotlinCompile>().matching { it.name.contains("Micronaut5Test") }.configureEach {
    compilerOptions.freeCompilerArgs.add("-Xskip-metadata-version-check")
}
tasks
    .matching { it.name.contains("Micronaut5Test") && it is UsesKotlinJavaToolchain }
    .configureEach { (this as UsesKotlinJavaToolchain).kotlinJavaToolchain.toolchain.use(java25Launcher) }
afterEvaluate {
    configurations
        .matching { it.name.startsWith("micronaut5Test") || it.name.startsWith("kaptMicronaut5Test") }
        .configureEach { if (isCanBeResolved) attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25) }
}

registerMicronautTests("test", micronaut4Version, onJava25 = false)
registerMicronautTests("micronaut40Test", "4.0.0", onJava25 = false)
registerMicronautTests("micronaut5Test", micronaut5Version, onJava25 = true)
