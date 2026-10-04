plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
rootProject.name = "otherlode-agent"

include("bootstrap")
include("wire")
include("fixtures-scala3")
include("fixtures-scala2")
include("fixtures-kotlin-jvm-default-disable")
include("fixtures-kotlin-class-sam")

include("fixtures-kotlinc")
project(":fixtures-kotlinc").projectDir = file("fixtures-compilers/kotlinc")
include("fixtures-javac")
project(":fixtures-javac").projectDir = file("fixtures-compilers/javac")
include("fixtures-scalac")
project(":fixtures-scalac").projectDir = file("fixtures-compilers/scalac")
include("demo")
include("demo-spring")
include("testkit")
include("endpoints-api")
include("endpoints-jdk-httpserver")
include("endpoints-spring-webmvc")
include("endpoints-ktor-2")
include("endpoints-ktor-3")
include("endpoints-jaxrs")
include("endpoints-otel-bridge")
