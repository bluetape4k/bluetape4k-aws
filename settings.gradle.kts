pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
    plugins {
        id("org.gradle.toolchains.foojay-resolver-convention") version ("1.0.0")
    }
}

rootProject.name = "bluetape4k-aws"

include(
    "aws",
    "aws-kotlin",
    "aws-spring-boot",
    "aws-ktor",
)

include("bluetape4k-aws-bom")
project(":bluetape4k-aws-bom").projectDir = file("bom")

include("aws-ktor-s3-examples")
project(":aws-ktor-s3-examples").projectDir = file("examples/aws-ktor-s3-examples")
