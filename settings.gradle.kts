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

include("aws-spring-boot-sqs-examples")
project(":aws-spring-boot-sqs-examples").projectDir = file("examples/aws-spring-boot-sqs-examples")

include("aws-ktor-sqs-examples")
project(":aws-ktor-sqs-examples").projectDir = file("examples/aws-ktor-sqs-examples")

include("aws-ktor-dynamodb-examples")
project(":aws-ktor-dynamodb-examples").projectDir = file("examples/aws-ktor-dynamodb-examples")

include("aws-spring-boot-dynamodb-examples")
project(":aws-spring-boot-dynamodb-examples").projectDir = file("examples/aws-spring-boot-dynamodb-examples")
