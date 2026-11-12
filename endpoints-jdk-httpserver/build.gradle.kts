plugins {
    kotlin("jvm") version "2.2.21"
}

group = "dev.otherlode"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":endpoints-api"))

    // At runtime OtherlodeEndpoints comes from the target JVM's bootstrap classloader.
    compileOnly(project(":bootstrap"))

    // At runtime the test must reach the one copy BootstrapHolder puts on the bootstrap loader.
    testCompileOnly(project(":bootstrap"))

    compileOnly("net.bytebuddy:byte-buddy:1.18.12")

    testImplementation(project(":"))
    testImplementation(kotlin("test"))

    testImplementation(project(":fixtures-kotlin-class-sam"))

    testImplementation("net.bytebuddy:byte-buddy-agent:1.18.12")
    testImplementation("net.bytebuddy:byte-buddy:1.18.12")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Djdk.attach.allowAttachSelf=true")
    // The lambda factory hook changes java.lang.invoke for the whole JVM, and each test must install
    // its endpoint advice before the JDK's ServerImpl first loads.
    forkEvery = 1
}
