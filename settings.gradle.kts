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

val bluetape4kDependenciesCatalogRef = providers.gradleProperty("bluetape4kDependenciesCatalogRef")
    .orElse(providers.environmentVariable("BLUETAPE4K_DEPENDENCIES_CATALOG_REF"))
    .orElse("catalog/2026-06-25-05")
    .get()
val bluetape4kDependenciesCatalogCacheKey = bluetape4kDependenciesCatalogRef.replace(Regex("[^A-Za-z0-9._-]"), "_")

fun resolveBluetape4kDependenciesCatalogFile(): File {
    providers.gradleProperty("bluetape4kDependenciesCatalogPath")
        .orElse(providers.environmentVariable("BLUETAPE4K_DEPENDENCIES_CATALOG_PATH"))
        .orNull
        ?.let(::file)
        ?.let { return it }

    listOf(
        "../bluetape4k-dependencies/gradle/libs.versions.toml",
        "bluetape4k-dependencies/gradle/libs.versions.toml",
    ).map(::file).firstOrNull { it.isFile }?.let { return it }

    val catalogFile = file(".gradle/bluetape4k-dependencies/$bluetape4kDependenciesCatalogCacheKey/libs.versions.toml")
    if (!catalogFile.isFile) {
        catalogFile.parentFile.mkdirs()
        val catalogUrl =
            "https://raw.githubusercontent.com/bluetape4k/bluetape4k-dependencies/$bluetape4kDependenciesCatalogRef/gradle/libs.versions.toml"
        uri(catalogUrl).toURL().openStream().use { input ->
            catalogFile.outputStream().use { output -> input.copyTo(output) }
        }
    }
    return catalogFile
}

val bluetape4kDependenciesCatalogFile = resolveBluetape4kDependenciesCatalogFile()

require(bluetape4kDependenciesCatalogFile.isFile) {
    "bluetape4k-dependencies catalog not found: $bluetape4kDependenciesCatalogFile. " +
        "Checkout bluetape4k-dependencies at the release-train tag or set bluetape4kDependenciesCatalogPath."
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://central.sonatype.com/repository/maven-snapshots/")
    }
    versionCatalogs {
        create("bt4k") {
            from(files(bluetape4kDependenciesCatalogFile))
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
project(":bluetape4k-aws-java").projectDir = file("aws-java")
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
