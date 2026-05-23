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

val bluetape4kDependenciesVersion = providers.gradleProperty("bluetape4kDependenciesVersion").get()

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://central.sonatype.com/repository/maven-snapshots/")
    }
    versionCatalogs {
        create("bt4k") {
            from("io.github.bluetape4k:bluetape4k-version-catalog:$bluetape4kDependenciesVersion")
        }
    }
}

rootProject.name = "bluetape4k-aws"

include(
    "bluetape4k-aws-java",
    "bluetape4k-aws-kotlin",
    "bluetape4k-aws-exposed",
    "bluetape4k-aws-spring-boot",
    "bluetape4k-aws-ktor",
)
project(":bluetape4k-aws-java").projectDir = file("aws")
project(":bluetape4k-aws-kotlin").projectDir = file("aws-kotlin")
project(":bluetape4k-aws-exposed").projectDir = file("aws-exposed")
project(":bluetape4k-aws-spring-boot").projectDir = file("aws-spring-boot")
project(":bluetape4k-aws-ktor").projectDir = file("aws-ktor")

include("bluetape4k-aws-bom")
project(":bluetape4k-aws-bom").projectDir = file("bom")

include("aws-ktor-s3-examples")
project(":aws-ktor-s3-examples").projectDir = file("examples/aws-ktor-s3-examples")

include("aws-spring-boot-s3-examples")
project(":aws-spring-boot-s3-examples").projectDir = file("examples/aws-spring-boot-s3-examples")

include("aws-spring-boot-exposed-examples")
project(":aws-spring-boot-exposed-examples").projectDir = file("examples/aws-spring-boot-exposed-examples")

include("aws-spring-boot-sqs-examples")
project(":aws-spring-boot-sqs-examples").projectDir = file("examples/aws-spring-boot-sqs-examples")

include("aws-ktor-sqs-examples")
project(":aws-ktor-sqs-examples").projectDir = file("examples/aws-ktor-sqs-examples")

include("aws-ktor-dynamodb-examples")
project(":aws-ktor-dynamodb-examples").projectDir = file("examples/aws-ktor-dynamodb-examples")

include("aws-ktor-exposed-examples")
project(":aws-ktor-exposed-examples").projectDir = file("examples/aws-ktor-exposed-examples")

include("aws-spring-boot-dynamodb-examples")
project(":aws-spring-boot-dynamodb-examples").projectDir = file("examples/aws-spring-boot-dynamodb-examples")
