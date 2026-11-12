plugins {
    kotlin("jvm") version "2.2.21"
    `jvm-test-suite`
}

group = "dev.otherlode"

repositories {
    mavenCentral()
}

val kotlinVersion = "2.2.21"
val byteBuddyVersion = "1.18.12"
val coroutinesVersion = "1.10.2"

// The framework canary sets these to the newest release of each line. :endpoints-spring-webmvc
// reads the same properties.
val spring53Version = providers.gradleProperty("otherlode.framework.spring53").getOrElse("5.3.39")
val spring6Version = providers.gradleProperty("otherlode.framework.spring6").getOrElse("6.2.19")
val spring7Version = providers.gradleProperty("otherlode.framework.spring7").getOrElse("7.0.9")

dependencies {
    implementation(project(":endpoints-api"))
    implementation(project(":endpoints-spring-common"))

    // Compile-time only: at runtime OtherlodeEndpoints comes from the target JVM's bootstrap classloader.
    compileOnly(project(":bootstrap"))

    compileOnly("net.bytebuddy:byte-buddy:$byteBuddyVersion")

    // The oldest supported release. Never `implementation`: Spring must not be shaded into the agent jar.
    compileOnly("org.springframework:spring-webflux:5.3.39")
    compileOnly("org.springframework:spring-context:5.3.39")

    testImplementation(project(":"))
    testImplementation(kotlin("test"))

    testImplementation("net.bytebuddy:byte-buddy-agent:$byteBuddyVersion")
    testImplementation("net.bytebuddy:byte-buddy:$byteBuddyVersion")

    testImplementation("org.springframework:spring-webflux:$spring6Version")
    testImplementation("org.springframework:spring-context:$spring6Version")
    testImplementation("org.springframework:spring-test:$spring6Version")

    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:$coroutinesVersion")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Djdk.attach.allowAttachSelf=true")
}

testing {
    suites {
        register<JvmTestSuite>("spring7Test") {
            useJUnitJupiter()
            sources {
                kotlin.srcDir("src/test/kotlin")
            }
            dependencies {
                implementation(project())
                implementation(project(":"))
                implementation(project(":endpoints-api"))
                implementation("org.jetbrains.kotlin:kotlin-test-junit5:$kotlinVersion")
                implementation("net.bytebuddy:byte-buddy-agent:$byteBuddyVersion")
                implementation("net.bytebuddy:byte-buddy:$byteBuddyVersion")
                implementation("org.springframework:spring-webflux:$spring7Version")
                implementation("org.springframework:spring-context:$spring7Version")
                implementation("org.springframework:spring-test:$spring7Version")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:$coroutinesVersion")
            }
            targets {
                all {
                    testTask.configure {
                        jvmArgs("-Djdk.attach.allowAttachSelf=true")
                    }
                }
            }
        }

        register<JvmTestSuite>("spring53Test") {
            useJUnitJupiter()
            sources {
                kotlin.srcDir("src/test/kotlin")
            }
            dependencies {
                implementation(project())
                implementation(project(":"))
                implementation(project(":endpoints-api"))
                implementation("org.jetbrains.kotlin:kotlin-test-junit5:$kotlinVersion")
                implementation("net.bytebuddy:byte-buddy-agent:$byteBuddyVersion")
                implementation("net.bytebuddy:byte-buddy:$byteBuddyVersion")
                implementation("org.springframework:spring-webflux:$spring53Version")
                implementation("org.springframework:spring-context:$spring53Version")
                implementation("org.springframework:spring-test:$spring53Version")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:$coroutinesVersion")
            }
            targets {
                all {
                    testTask.configure {
                        jvmArgs("-Djdk.attach.allowAttachSelf=true")
                    }
                }
            }
        }
    }
}

tasks.check {
    dependsOn(testing.suites.named("spring7Test"))
    dependsOn(testing.suites.named("spring53Test"))
}
