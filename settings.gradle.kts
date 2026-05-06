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
