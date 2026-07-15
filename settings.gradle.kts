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
    .orElse("0416edc348a2fd44b4e2654aa9e392247d7a43bf")
    .get()
val bluetape4kDependenciesCatalogCacheKey = bluetape4kDependenciesCatalogRef.replace(Regex("[^A-Za-z0-9._-]"), "_")

fun catalogSha256(file: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

fun expectedCatalogSha256(checksumFile: File): String? =
    checksumFile.takeIf(File::isFile)
        ?.readText()
        ?.trim()
        ?.split(Regex("\\s+"))
        ?.firstOrNull()
        ?.lowercase()
        ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }

fun catalogChecksumMatches(catalogFile: File, checksumFile: File): Boolean =
    catalogFile.isFile && expectedCatalogSha256(checksumFile)?.let { it == catalogSha256(catalogFile) } == true

fun downloadCatalogFile(url: String, target: File) {
    uri(url).toURL().openStream().use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    }
}

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
    val checksumFile = file(".gradle/bluetape4k-dependencies/$bluetape4kDependenciesCatalogCacheKey/libs.versions.toml.sha256")
    if (!catalogChecksumMatches(catalogFile, checksumFile)) {
        require(catalogFile.parentFile.mkdirs() || catalogFile.parentFile.isDirectory) {
            "Cannot create bluetape4k-dependencies catalog cache: ${catalogFile.parentFile}"
        }
        val catalogBaseUrl =
            "https://raw.githubusercontent.com/bluetape4k/bluetape4k-dependencies/$bluetape4kDependenciesCatalogRef/gradle"
        val catalogTempFile = File.createTempFile("libs.versions-", ".toml.tmp", catalogFile.parentFile)
        val checksumTempFile = File.createTempFile("libs.versions-", ".sha256.tmp", catalogFile.parentFile)
        try {
            downloadCatalogFile("$catalogBaseUrl/libs.versions.toml", catalogTempFile)
            downloadCatalogFile("$catalogBaseUrl/libs.versions.toml.sha256", checksumTempFile)
            val expectedChecksum = requireNotNull(expectedCatalogSha256(checksumTempFile)) {
                "Invalid bluetape4k-dependencies catalog checksum: $checksumTempFile"
            }
            require(catalogSha256(catalogTempFile) == expectedChecksum) {
                "bluetape4k-dependencies catalog checksum mismatch for ref $bluetape4kDependenciesCatalogRef"
            }
            java.nio.file.Files.move(
                checksumTempFile.toPath(),
                checksumFile.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            java.nio.file.Files.move(
                catalogTempFile.toPath(),
                catalogFile.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            catalogTempFile.delete()
            checksumTempFile.delete()
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

include("aws-ktor-service-coverage-examples")
project(":aws-ktor-service-coverage-examples").projectDir = file("examples/aws-ktor-service-coverage-examples")

include("aws-spring-boot-dynamodb-examples")
project(":aws-spring-boot-dynamodb-examples").projectDir = file("examples/aws-spring-boot-dynamodb-examples")
