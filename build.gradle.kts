import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension
import dev.detekt.gradle.report.ReportMergeTask
import nmcp.NmcpAggregationExtension
import nmcp.NmcpExtension
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.security.MessageDigest
import java.util.jar.JarFile
import java.util.concurrent.TimeUnit

abstract class VerifyDetektCoverage : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val moduleSourceDirectories: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val moduleReports: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mergedReport: RegularFileProperty

    @get:Input
    abstract val expectedModuleCount: Property<Int>

    @TaskAction
    fun verifyCoverage() {
        val sourceDirectories = moduleSourceDirectories.files
        require(sourceDirectories.size == expectedModuleCount.get()) {
            "Expected ${expectedModuleCount.get()} Detekt module source directories, found ${sourceDirectories.size}."
        }
        val noSourceModules = sourceDirectories
            .filter { directory -> directory.walkTopDown().none { it.isFile && it.extension == "kt" } }
            .map(File::getName)
        require(noSourceModules.isEmpty()) {
            "Detekt has no Kotlin sources for published modules: ${noSourceModules.joinToString()}"
        }
        require(moduleReports.files.size == expectedModuleCount.get() && moduleReports.files.all(File::isFile)) {
            "Detekt XML reports are missing for one or more published modules."
        }
        require(mergedReport.get().asFile.isFile) {
            "Merged Detekt report was not created."
        }
    }
}

abstract class VerifyLegacyAbiTask : DefaultTask() {
    @get:Internal
    abstract val repositoryDirectory: DirectoryProperty

    @get:Input
    abstract val moduleDirectory: Property<String>

    @get:Input
    abstract val className: Property<String>

    @get:Input
    abstract val classEntry: Property<String>

    @get:Input
    abstract val fixturePath: Property<String>

    @get:Input
    abstract val enforceImplementationBaseline: Property<Boolean>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fixtureDirectory: DirectoryProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun normalizeJavap(output: String): String = output
        .lineSequence()
        .filterNot { line -> line.startsWith("Compiled from ") }
        .filter { line -> line.isNotBlank() }
        .joinToString("\n") { line -> line.trimEnd() }

    @TaskAction
    fun verifyLegacyAbi() {
        val fixtureDirectory = fixtureDirectory.get().asFile
        val repositoryDirectory = repositoryDirectory.get().asFile
        val jar = File(repositoryDirectory, "${moduleDirectory.get()}/build/libs")
            .listFiles { candidate -> candidate.extension == "jar" }
            ?.toList()
            ?.singleOrNull()
            ?: error("Expected one ${moduleDirectory.get()} JAR under build/libs")
        val sourceHash = sha256Hex(sourceFile.get().asFile.readBytes())
        val expectedSourceHash = File(fixtureDirectory, "source.sha256").readText().trim()
        val sourceMatches = sourceHash == expectedSourceHash
        val bytecode = JarFile(jar).use { archive ->
            val entry = archive.getJarEntry(classEntry.get())
                ?: error("Missing ${classEntry.get()} in ${jar.name}")
            archive.getInputStream(entry).use { it.readBytes() }
        }
        val bytecodeHash = sha256Hex(bytecode)
        val expectedBytecodeHash = File(fixtureDirectory, "bytecode.sha256").readText().trim()
        val bytecodeMatches = bytecodeHash == expectedBytecodeHash
        if (enforceImplementationBaseline.get()) {
            require(sourceMatches) {
                "${className.get()} source implementation baseline changed: expected $expectedSourceHash, actual $sourceHash"
            }
            require(bytecodeMatches) {
                "${className.get()} bytecode implementation baseline changed: expected $expectedBytecodeHash, actual $bytecodeHash"
            }
        }
        val javap = ProcessBuilder("javap", "-classpath", jar.absolutePath, "-public", className.get())
            .redirectErrorStream(true)
            .start()
        val javapOutput = javap.inputStream.bufferedReader().use { it.readText() }
        require(javap.waitFor() == 0) { "javap failed for ${className.get()}: $javapOutput" }
        val expectedSignature = normalizeJavap(File(fixtureDirectory, "javap.txt").readText())
        val actualSignature = normalizeJavap(javapOutput)
        require(actualSignature == expectedSignature) {
            "${className.get()} public signature changed.\nExpected:\n$expectedSignature\nActual:\n$actualSignature"
        }
        require("SqsExtended" !in actualSignature && "S3Extended" !in actualSignature) {
            "Legacy ABI fixture unexpectedly references Issue #455 extension types"
        }
        reportFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                groovy.json.JsonOutput.prettyPrint(
                    groovy.json.JsonOutput.toJson(
                        linkedMapOf(
                            "issue" to 455,
                            "className" to className.get(),
                            "implementationBaseline" to linkedMapOf(
                                "enforced" to enforceImplementationBaseline.get(),
                                "sourceSha256" to sourceHash,
                                "expectedSourceSha256" to expectedSourceHash,
                                "sourceMatch" to sourceMatches,
                                "bytecodeSha256" to bytecodeHash,
                                "expectedBytecodeSha256" to expectedBytecodeHash,
                                "bytecodeMatch" to bytecodeMatches,
                            ),
                            "publicSignature" to "matched",
                            "fixture" to fixturePath.get(),
                        ),
                    ),
                ) + "\n",
            )
        }
    }
}

abstract class VerifyAdditiveKinesisAbiTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val productionJar: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baselineFiles: ConfigurableFileCollection

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    private data class ExpectedMethod(
        val owner: String,
        val signature: String,
        val descriptor: String,
    )

    private fun parseMethods(owner: String, lines: List<String>, source: String): List<ExpectedMethod> {
        return lines.mapIndexedNotNull { index, line ->
            if (!line.startsWith("public static ")) {
                null
            } else {
                ExpectedMethod(
                    owner = owner,
                    signature = line.trim(),
                    descriptor = lines.getOrNull(index + 1)?.trim()
                        ?.takeIf { it.startsWith("descriptor: ") }
                        ?: error("Missing descriptor after $source:${index + 1}"),
                )
            }
        }
    }

    private fun readExpectedMethods(file: File): List<ExpectedMethod> {
        val lines = file.readLines()
        val owner = lines.single { it.startsWith("CLASS ") }.removePrefix("CLASS ")
        return parseMethods(owner, lines, file.path)
    }

    @TaskAction
    fun verifyAdditiveAbi() {
        val jar = productionJar.get().asFile
        val expected = baselineFiles.files.sortedBy(File::getName).flatMap(::readExpectedMethods)
        require(expected.size == 12) {
            "Expected exactly 12 pre-change Kinesis methods, found ${expected.size}"
        }

        expected.groupBy(ExpectedMethod::owner).forEach { (owner, methods) ->
            val javap = ProcessBuilder("javap", "-classpath", jar.absolutePath, "-public", "-s", owner)
                .redirectErrorStream(true)
                .start()
            val output = javap.inputStream.bufferedReader().use { it.readText() }
            require(javap.waitFor() == 0) { "javap failed for $owner: $output" }
            val normalized = output.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
            val actual = parseMethods(owner, normalized, "javap:$owner")
            methods.forEach { method ->
                require(method in actual) {
                    "Missing pre-change Kinesis ABI method in ${jar.name}:\n${method.signature}\n${method.descriptor}"
                }
            }
        }

        reportFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                groovy.json.JsonOutput.prettyPrint(
                    groovy.json.JsonOutput.toJson(
                        linkedMapOf(
                            "issue" to 620,
                            "status" to "passed",
                            "policy" to "additive",
                            "legacyMethodCount" to expected.size,
                            "owners" to expected.map(ExpectedMethod::owner).distinct().sorted(),
                        ),
                    ),
                ) + "\n",
            )
        }
    }
}

abstract class VerifyKinesisDryRunFixtureIntegrityTask : DefaultTask() {
    @get:Internal
    abstract val fixtureRoot: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fixtureFiles: ConfigurableFileCollection

    @get:Input
    abstract val expectedSha256: MapProperty<String, String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { byte -> "%02x".format(byte) }

    @TaskAction
    fun verifyIntegrity() {
        val root = fixtureRoot.get().asFile.toPath()
        val actual = fixtureFiles.files.associate { file ->
            root.relativize(file.toPath()).toString() to sha256(file)
        }.toSortedMap()
        val expected = expectedSha256.get().toSortedMap()
        require(actual.keys == expected.keys) {
            "Kinesis pre-change fixture file set changed: expected=${expected.keys}, actual=${actual.keys}"
        }
        val drift = actual.filter { (path, digest) -> expected[path] != digest }
        require(drift.isEmpty()) {
            "Kinesis pre-change fixture digest changed: " +
                drift.entries.joinToString { (path, digest) -> "$path=$digest expected=${expected[path]}" }
        }

        reportFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                groovy.json.JsonOutput.prettyPrint(
                    groovy.json.JsonOutput.toJson(
                        linkedMapOf(
                            "issue" to 620,
                            "status" to "passed",
                            "baseCommit" to "f07015b6e9a3e6aceb4f301081b502cb88eb40c3",
                            "sha256" to actual,
                        ),
                    ),
                ) + "\n",
            )
        }
    }
}

data class KinesisLegacyReference(
    val owner: String,
    val method: String,
    val descriptor: String,
)

abstract class WriteCompatibilityReportTask : DefaultTask() {
    @get:Input
    abstract val checks: ListProperty<String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun writeReport() {
        reportFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                groovy.json.JsonOutput.prettyPrint(
                    groovy.json.JsonOutput.toJson(
                        linkedMapOf(
                            "status" to "passed",
                            "checks" to checks.get(),
                            "optionalSdkIsolation" to "aws-spring-boot:compatibilityTest",
                            "baselinePolicy" to "public signature gate is separate from implementation baseline audit",
                        ),
                    ),
                ) + "\n",
            )
        }
    }
}

plugins {
    base
    `maven-publish`
    signing
    alias(bt4k.plugins.kotlin.jvm)

    alias(bt4k.plugins.kotlin.spring) apply false
    alias(bt4k.plugins.kotlin.allopen) apply false
    alias(bt4k.plugins.kotlin.noarg) apply false
    alias(bt4k.plugins.kotlinx.atomicfu)

    alias(bt4k.plugins.detekt.dev) apply false
    alias(bt4k.plugins.dependency.management)

    alias(bt4k.plugins.dokka)
    alias(bt4k.plugins.test.logger)

    alias(bt4k.plugins.nmcp.aggregation)
    alias(bt4k.plugins.nmcp) apply false

    alias(bt4k.plugins.kover) apply false
    alias(bt4k.plugins.graalvm.native) apply false
    alias(bt4k.plugins.exposed.plugin) apply false
}

val rootLibs = libs
val rootBt4k = bt4k
val rootDependencies = dependencies
val bt4kCatalog = extensions.getByType<org.gradle.api.artifacts.VersionCatalogsExtension>().named("bt4k")
fun bt4kLibrary(alias: String) = bt4kCatalog.findLibrary(alias).get()
fun bt4kVersion(alias: String): String {
    val version = bt4kCatalog.findVersion(alias).get()
    return version.requiredVersion
        .ifBlank { version.preferredVersion }
        .ifBlank { version.strictVersion }
}

val requestedTaskNames = gradle.startParameter.taskNames
val shouldApplyDetekt = requestedTaskNames.any { it.contains("detekt", ignoreCase = true) }
val shouldApplyKover = requestedTaskNames.any {
    it.contains("kover", ignoreCase = true) || it.contains("coverage", ignoreCase = true)
}
val shouldApplyNative = requestedTaskNames.any {
    it.contains("native", ignoreCase = true) ||
        it.contains("processAot", ignoreCase = true) ||
        it.contains("processTestAot", ignoreCase = true)
}

if (shouldApplyKover) {
    apply(plugin = "org.jetbrains.kotlinx.kover")
}

val publishedKotlinProjects = subprojects.filter { project ->
    project.name != "bluetape4k-aws-bom" && !project.path.contains("examples")
}
val detektReportMerge = tasks.register<ReportMergeTask>("detektReportMerge") {
    description = "Merges Detekt XML reports from published Kotlin modules."
    group = "verification"
    output.set(layout.buildDirectory.file("reports/detekt/merged.xml"))
}
val verifyDetektCoverage = tasks.register<VerifyDetektCoverage>("verifyDetektCoverage") {
    description = "Verifies Detekt analyzes every published Kotlin module and produces reports."
    group = "verification"
    dependsOn(detektReportMerge)
    expectedModuleCount.set(publishedKotlinProjects.size)
    moduleSourceDirectories.from(publishedKotlinProjects.map { it.layout.projectDirectory.dir("src") })
    mergedReport.set(detektReportMerge.flatMap { it.output })
}
tasks.register("detekt") {
    description = "Runs Detekt for every published Kotlin module and verifies the merged report."
    group = "verification"
    dependsOn(verifyDetektCoverage)
}


val centralPublishing = resolveCentralPublishingConfig()
val centralUser: String = centralPublishing.username
val centralPassword: String = centralPublishing.password
val centralSnapshotsParallelism: Int = providers
    .gradleProperty("centralSnapshotsParallelism")
    .map(String::toInt)
    .orElse(4)
    .get()

val projectGroup: String = providers.gradleProperty("projectGroup").get()
val baseVersion: String = providers.gradleProperty("baseVersion").get()
val snapshotVersion: String = providers.gradleProperty("snapshotVersion").get()

val awsKtorSqsConsumerFixtureClasspath = configurations.create("awsKtorSqsConsumerFixtureClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
    resolutionStrategy.eachDependency {
        when (requested.group) {
            "software.amazon.awssdk" -> useVersion(bt4kVersion("aws2"))
            "aws.sdk.kotlin" -> useVersion(bt4kVersion("aws-kotlin"))
            "io.ktor" -> useVersion(bt4kVersion("ktor"))
            "org.jetbrains.kotlinx" -> if (requested.name.startsWith("kotlinx-coroutines")) {
                useVersion(bt4kVersion("kotlinx-coroutines"))
            }
        }
        because("consumer fixture resolves versions from the central bt4k catalog")
    }
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
    }
}

val awsSpringSqsConsumerFixtureClasspath = configurations.create("awsSpringSqsConsumerFixtureClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
    resolutionStrategy.eachDependency {
        when (requested.group) {
            "software.amazon.awssdk" -> useVersion(bt4kVersion("aws2"))
            "org.jetbrains.kotlinx" -> if (requested.name.startsWith("kotlinx-coroutines")) {
                useVersion(bt4kVersion("kotlinx-coroutines"))
            }
        }
        because("consumer fixture resolves versions from the central bt4k catalog")
    }
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
    }
}

val awsSpringSnsConsumerFixtureClasspath = configurations.create("awsSpringSnsConsumerFixtureClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
    resolutionStrategy.eachDependency {
        when (requested.group) {
            "software.amazon.awssdk" -> useVersion(bt4kVersion("aws2"))
            "org.jetbrains.kotlinx" -> if (requested.name.startsWith("kotlinx-coroutines")) {
                useVersion(bt4kVersion("kotlinx-coroutines"))
            }
        }
        because("consumer fixture resolves versions from the central bt4k catalog")
    }
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
    }
}

val awsSpringModulithConsumerFixtureClasspath = configurations.create("awsSpringModulithConsumerFixtureClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
    resolutionStrategy.eachDependency {
        when (requested.group) {
            "software.amazon.awssdk" -> useVersion(bt4kVersion("aws2"))
            "org.jetbrains.kotlinx" -> if (requested.name.startsWith("kotlinx-coroutines")) {
                useVersion(bt4kVersion("kotlinx-coroutines"))
            }
        }
        because("consumer fixture resolves versions from the central bt4k catalog")
    }
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
    }
}

fun Configuration.configureBedrockConsumerFixtureVersions() {
    isCanBeConsumed = false
    isCanBeResolved = true
    resolutionStrategy.eachDependency {
        when (requested.group) {
            "software.amazon.awssdk" -> useVersion(bt4kVersion("aws2"))
            "aws.sdk.kotlin" -> useVersion(bt4kVersion("aws-kotlin"))
            "org.jetbrains.kotlinx" -> if (requested.name.startsWith("kotlinx-coroutines")) {
                useVersion(bt4kVersion("kotlinx-coroutines"))
            }
        }
        because("consumer fixture resolves versions from the central bt4k catalog")
    }
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
    }
}

val bedrockJavaConsumerFixtureClasspath =
    configurations.create("bedrockJavaConsumerFixtureClasspath") {
        configureBedrockConsumerFixtureVersions()
    }
val bedrockKotlinConsumerFixtureClasspath =
    configurations.create("bedrockKotlinConsumerFixtureClasspath") {
        configureBedrockConsumerFixtureVersions()
    }

val omittedConsumerFixtureService = providers.gradleProperty("consumerFixtureOmit").orNull

fun Configuration.configureAwsServiceConsumerFixtureVersions() {
    isCanBeConsumed = false
    isCanBeResolved = true
    resolutionStrategy.eachDependency {
        when (requested.group) {
            "software.amazon.awssdk" -> useVersion(bt4kVersion("aws2"))
            "aws.sdk.kotlin" -> useVersion(bt4kVersion("aws-kotlin"))
            "org.jetbrains.kotlinx" -> if (requested.name.startsWith("kotlinx-coroutines")) {
                useVersion(bt4kVersion("kotlinx-coroutines"))
            }
        }
        because("consumer fixture resolves versions from the central bt4k catalog")
    }
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
    }
}

val awsJavaServiceConsumerFixtureClasspath =
    configurations.create("awsJavaServiceConsumerFixtureClasspath") {
        configureAwsServiceConsumerFixtureVersions()
    }
val awsKotlinServiceConsumerFixtureClasspath =
    configurations.create("awsKotlinServiceConsumerFixtureClasspath") {
        configureAwsServiceConsumerFixtureVersions()
    }
val kinesisDryRunLegacyDependencies = configurations.create("kinesisDryRunLegacyDependencies") {
    configureAwsServiceConsumerFixtureVersions()
}

fun addConsumerFixtureDependency(
    configuration: Configuration,
    serviceKey: String,
    dependency: Any,
) {
    if (omittedConsumerFixtureService != serviceKey) {
        rootDependencies.add(configuration.name, dependency)
    }
}

dependencies {
    awsKtorSqsConsumerFixtureClasspath(project(":bluetape4k-aws-ktor"))
    awsKtorSqsConsumerFixtureClasspath(libs.ktor.server.core)

    awsSpringSqsConsumerFixtureClasspath(project(":bluetape4k-aws-spring-boot"))
    awsSpringSqsConsumerFixtureClasspath(platform(bt4k.spring.boot4.dependencies))
    awsSpringSqsConsumerFixtureClasspath(libs.aws2.sqs)
    awsSpringSqsConsumerFixtureClasspath(libs.kotlinx.coroutines.core)
    awsSpringSqsConsumerFixtureClasspath(libs.spring.context.support)
    awsSpringSqsConsumerFixtureClasspath(bt4k.bluetape4k.io)
    awsSpringSqsConsumerFixtureClasspath(bt4k.bluetape4k.coroutines)

    awsSpringSnsConsumerFixtureClasspath(project(":bluetape4k-aws-spring-boot"))
    awsSpringSnsConsumerFixtureClasspath(platform(bt4k.spring.boot4.dependencies))
    awsSpringSnsConsumerFixtureClasspath(libs.aws2.sns)
    awsSpringSnsConsumerFixtureClasspath(libs.kotlinx.coroutines.core)
    awsSpringSnsConsumerFixtureClasspath(libs.spring.context.support)
    awsSpringSnsConsumerFixtureClasspath(bt4k.bluetape4k.io)
    awsSpringSnsConsumerFixtureClasspath(bt4k.bluetape4k.coroutines)

    awsSpringModulithConsumerFixtureClasspath(project(":bluetape4k-aws-spring-boot"))
    awsSpringModulithConsumerFixtureClasspath(platform(bt4k.spring.boot4.dependencies))
    awsSpringModulithConsumerFixtureClasspath(libs.aws2.sns)
    awsSpringModulithConsumerFixtureClasspath(libs.aws2.sqs)
    awsSpringModulithConsumerFixtureClasspath(libs.aws2.sns.message.manager)
    awsSpringModulithConsumerFixtureClasspath(platform(bt4k.spring.modulith.bom))
    awsSpringModulithConsumerFixtureClasspath(libs.spring.modulith.events.api)
    awsSpringModulithConsumerFixtureClasspath(libs.spring.modulith.events.core)
    awsSpringModulithConsumerFixtureClasspath(libs.spring.modulith.events.jackson)
    awsSpringModulithConsumerFixtureClasspath(libs.kotlinx.coroutines.core)
    awsSpringModulithConsumerFixtureClasspath(libs.spring.context.support)

    bedrockJavaConsumerFixtureClasspath(project(":bluetape4k-aws-java"))
    bedrockJavaConsumerFixtureClasspath(libs.aws2.bedrock.runtime)
    bedrockJavaConsumerFixtureClasspath(bt4kLibrary("bluetape4k-coroutines"))
    bedrockJavaConsumerFixtureClasspath(libs.kotlinx.coroutines.core)
    bedrockJavaConsumerFixtureClasspath(libs.kotlinx.coroutines.reactive)

    bedrockKotlinConsumerFixtureClasspath(project(":bluetape4k-aws-kotlin"))
    bedrockKotlinConsumerFixtureClasspath(libs.aws.kotlin.bedrock.runtime)
    bedrockKotlinConsumerFixtureClasspath(bt4kLibrary("bluetape4k-coroutines"))
    bedrockKotlinConsumerFixtureClasspath(libs.kotlinx.coroutines.core)

    addConsumerFixtureDependency(awsJavaServiceConsumerFixtureClasspath, "aws-java:module", project(":bluetape4k-aws-java"))
    addConsumerFixtureDependency(awsJavaServiceConsumerFixtureClasspath, "aws-java:s3", libs.aws2.s3)
    addConsumerFixtureDependency(awsJavaServiceConsumerFixtureClasspath, "aws-java:s3tables", libs.aws2.s3tables)
    addConsumerFixtureDependency(awsJavaServiceConsumerFixtureClasspath, "aws-java:s3-transfer", libs.aws2.s3.transfer.manager)
    addConsumerFixtureDependency(awsJavaServiceConsumerFixtureClasspath, "aws-java:crt", bt4k.aws2.aws.crt)
    addConsumerFixtureDependency(awsJavaServiceConsumerFixtureClasspath, "aws-java:dynamodb", libs.aws2.dynamodb.enhanced)
    addConsumerFixtureDependency(awsJavaServiceConsumerFixtureClasspath, "aws-java:sns", libs.aws2.sns)
    addConsumerFixtureDependency(awsJavaServiceConsumerFixtureClasspath, "aws-java:sqs", libs.aws2.sqs)
    addConsumerFixtureDependency(awsJavaServiceConsumerFixtureClasspath, "aws-java:kms", libs.aws2.kms)
    addConsumerFixtureDependency(awsJavaServiceConsumerFixtureClasspath, "aws-java:ses", libs.aws2.ses)
    addConsumerFixtureDependency(awsJavaServiceConsumerFixtureClasspath, "aws-java:cloudwatch", libs.aws2.cloudwatch)
    addConsumerFixtureDependency(awsJavaServiceConsumerFixtureClasspath, "aws-java:kinesis", libs.aws2.kinesis)
    addConsumerFixtureDependency(awsJavaServiceConsumerFixtureClasspath, "aws-java:scheduler", libs.aws2.scheduler)
    addConsumerFixtureDependency(awsJavaServiceConsumerFixtureClasspath, "aws-java:sfn", libs.aws2.sfn)
    addConsumerFixtureDependency(awsJavaServiceConsumerFixtureClasspath, "aws-java:lambda", libs.aws2.lambda)
    addConsumerFixtureDependency(awsJavaServiceConsumerFixtureClasspath, "aws-java:sts", libs.aws2.sts)
    awsJavaServiceConsumerFixtureClasspath(bt4kLibrary("bluetape4k-coroutines"))
    awsJavaServiceConsumerFixtureClasspath(libs.kotlinx.coroutines.core)

    addConsumerFixtureDependency(awsKotlinServiceConsumerFixtureClasspath, "aws-kotlin:module", project(":bluetape4k-aws-kotlin"))
    addConsumerFixtureDependency(awsKotlinServiceConsumerFixtureClasspath, "aws-kotlin:s3", libs.aws.kotlin.s3)
    addConsumerFixtureDependency(awsKotlinServiceConsumerFixtureClasspath, "aws-kotlin:s3tables", libs.aws.kotlin.s3tables)
    addConsumerFixtureDependency(awsKotlinServiceConsumerFixtureClasspath, "aws-kotlin:dynamodb", libs.aws.kotlin.dynamodb)
    addConsumerFixtureDependency(awsKotlinServiceConsumerFixtureClasspath, "aws-kotlin:dynamodbstreams", libs.aws.kotlin.dynamodbstreams)
    addConsumerFixtureDependency(awsKotlinServiceConsumerFixtureClasspath, "aws-kotlin:sns", libs.aws.kotlin.sns)
    addConsumerFixtureDependency(awsKotlinServiceConsumerFixtureClasspath, "aws-kotlin:sqs", libs.aws.kotlin.sqs)
    addConsumerFixtureDependency(awsKotlinServiceConsumerFixtureClasspath, "aws-kotlin:kms", libs.aws.kotlin.kms)
    addConsumerFixtureDependency(awsKotlinServiceConsumerFixtureClasspath, "aws-kotlin:ses", libs.aws.kotlin.ses)
    addConsumerFixtureDependency(awsKotlinServiceConsumerFixtureClasspath, "aws-kotlin:cloudwatch", libs.aws.kotlin.cloudwatch)
    addConsumerFixtureDependency(awsKotlinServiceConsumerFixtureClasspath, "aws-kotlin:kinesis", libs.aws.kotlin.kinesis)
    addConsumerFixtureDependency(awsKotlinServiceConsumerFixtureClasspath, "aws-kotlin:scheduler", libs.aws.kotlin.scheduler)
    addConsumerFixtureDependency(awsKotlinServiceConsumerFixtureClasspath, "aws-kotlin:sfn", libs.aws.kotlin.sfn)
    addConsumerFixtureDependency(awsKotlinServiceConsumerFixtureClasspath, "aws-kotlin:lambda", libs.aws.kotlin.lambda)
    addConsumerFixtureDependency(awsKotlinServiceConsumerFixtureClasspath, "aws-kotlin:sts", libs.aws.kotlin.sts)
    awsKotlinServiceConsumerFixtureClasspath(bt4kLibrary("bluetape4k-coroutines"))
    awsKotlinServiceConsumerFixtureClasspath(libs.kotlinx.coroutines.core)

    kinesisDryRunLegacyDependencies(libs.aws.kotlin.kinesis)
    kinesisDryRunLegacyDependencies(rootLibs.kotlin.stdlib)
}

val compileAwsKtorSqsConsumerFixture = tasks.register<JavaCompile>("compileAwsKtorSqsConsumerFixture") {
    description = "Compiles a minimal external SQS consumer against aws-ktor API dependencies."
    group = "verification"
    source(fileTree("aws-ktor/src/consumerFixture/java") { include("**/*.java") })
    classpath = awsKtorSqsConsumerFixtureClasspath
    destinationDirectory.set(layout.buildDirectory.dir("consumer-fixtures/aws-ktor-sqs/classes"))
    sourceCompatibility = "25"
    targetCompatibility = "25"
    options.encoding = "UTF-8"
}

fun registerSqsConsumerFixtureCompile(
    name: String,
    sourceFile: String,
    outputPath: String,
): TaskProvider<out Task> {
    val sourceSetName = name.removePrefix("compile").replaceFirstChar(Char::lowercase)
    val sourceSet: SourceSet = sourceSets.create(sourceSetName)
    tasks.named<JavaCompile>(sourceSet.compileJavaTaskName) {
        options.release.set(25)
    }
    val kotlinCompile = tasks.named<KotlinJvmCompile>(sourceSet.getCompileTaskName("kotlin")) {
        source(fileTree("aws-spring-boot/src/consumerFixture/kotlin") { include(sourceFile) })
        libraries.setFrom(awsSpringSqsConsumerFixtureClasspath)
        destinationDirectory.set(layout.buildDirectory.dir(outputPath))
        compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
        compilerOptions.freeCompilerArgs.add("-jvm-default=enable")
        dependsOn(":bluetape4k-aws-spring-boot:jar")
    }
    return tasks.register(name) {
        description = "Compiles an external SQS consumer compatibility fixture."
        group = "verification"
        dependsOn(kotlinCompile)
    }
}

fun registerSnsConsumerFixtureCompile(
    name: String,
    sourceFile: String,
    outputPath: String,
): TaskProvider<out Task> {
    val sourceSetName = name.removePrefix("compile").replaceFirstChar(Char::lowercase)
    val sourceSet: SourceSet = sourceSets.create(sourceSetName)
    tasks.named<JavaCompile>(sourceSet.compileJavaTaskName) {
        options.release.set(25)
    }
    val kotlinCompile = tasks.named<KotlinJvmCompile>(sourceSet.getCompileTaskName("kotlin")) {
        source(fileTree("aws-spring-boot/src/consumerFixture/kotlin") { include(sourceFile) })
        libraries.setFrom(awsSpringSnsConsumerFixtureClasspath)
        destinationDirectory.set(layout.buildDirectory.dir(outputPath))
        compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
        compilerOptions.freeCompilerArgs.add("-jvm-default=enable")
        dependsOn(":bluetape4k-aws-spring-boot:jar")
    }
    return tasks.register(name) {
        description = "Compiles an external SNS consumer compatibility fixture."
        group = "verification"
        dependsOn(kotlinCompile)
    }
}

val compileSqsOperationsLegacyConsumerFixture = registerSqsConsumerFixtureCompile(
    name = "compileSqsOperationsLegacyConsumerFixture",
    sourceFile = "io/bluetape4k/aws/spring/sqs/consumer/LegacySqsOperationsFixture.kt",
    outputPath = "consumer-fixtures/aws-spring-sqs/operations-legacy/classes",
)
val compileSqsPropertiesLegacyConsumerFixture = registerSqsConsumerFixtureCompile(
    name = "compileSqsPropertiesLegacyConsumerFixture",
    sourceFile = "io/bluetape4k/aws/spring/sqs/consumer/LegacySqsPropertiesFixture.kt",
    outputPath = "consumer-fixtures/aws-spring-sqs/properties-legacy/classes",
)
val compileSqsListenerAnnotationLegacyConsumerFixture = registerSqsConsumerFixtureCompile(
    name = "compileSqsListenerAnnotationLegacyConsumerFixture",
    sourceFile = "io/bluetape4k/aws/spring/sqs/consumer/LegacySqsListenerAnnotationFixture.kt",
    outputPath = "consumer-fixtures/aws-spring-sqs/annotation-legacy/classes",
)
val compileSqsListenerInterceptorLegacyConsumerFixture = registerSqsConsumerFixtureCompile(
    name = "compileSqsListenerInterceptorLegacyConsumerFixture",
    sourceFile = "io/bluetape4k/aws/spring/sqs/consumer/LegacySqsListenerInterceptorFixture.kt",
    outputPath = "consumer-fixtures/aws-spring-sqs/interceptor-legacy/classes",
)
val compileSqsBatchConsumerFixture = registerSqsConsumerFixtureCompile(
    name = "compileSqsBatchConsumerFixture",
    sourceFile = "io/bluetape4k/aws/spring/sqs/consumer/SqsBatchConsumerFixture.kt",
    outputPath = "consumer-fixtures/aws-spring-sqs/batch/classes",
)
val compileSnsOperationsLegacyConsumerFixture = registerSnsConsumerFixtureCompile(
    name = "compileSnsOperationsLegacyConsumerFixture",
    sourceFile = "io/bluetape4k/aws/spring/sns/consumer/LegacySnsOperationsFixture.kt",
    outputPath = "consumer-fixtures/aws-spring-sns/operations-legacy/classes",
)

fun registerSpringModulithConsumerFixtureCompile(
    name: String,
    sourceFile: String,
    outputPath: String,
): TaskProvider<out Task> {
    val sourceSetName = name.removePrefix("compile").replaceFirstChar(Char::lowercase)
    val sourceSet: SourceSet = sourceSets.create(sourceSetName)
    tasks.named<JavaCompile>(sourceSet.compileJavaTaskName) {
        options.release.set(25)
    }
    val kotlinCompile = tasks.named<KotlinJvmCompile>(sourceSet.getCompileTaskName("kotlin")) {
        source(file(sourceFile))
        libraries.setFrom(awsSpringModulithConsumerFixtureClasspath)
        destinationDirectory.set(layout.buildDirectory.dir(outputPath))
        compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
        compilerOptions.freeCompilerArgs.add("-jvm-default=enable")
        dependsOn(":bluetape4k-aws-spring-boot:jar")
    }
    return tasks.register(name) {
        description = "Compiles an external Spring Modulith consumer compatibility fixture."
        group = "verification"
        dependsOn(kotlinCompile)
    }
}

val compileAwsSpringModulithConsumerFixture = registerSpringModulithConsumerFixtureCompile(
    name = "compileAwsSpringModulithConsumerFixture",
    sourceFile = "aws-spring-boot/src/consumerFixture/kotlin/io/bluetape4k/aws/spring/modulith/consumer/AwsModulithConsumerFixture.kt",
    outputPath = "consumer-fixtures/aws-spring-modulith/classes",
)

val compileAwsSpringModulithForbiddenConfigurationConstructorFixture = registerSpringModulithConsumerFixtureCompile(
    name = "compileAwsSpringModulithForbiddenConfigurationConstructorFixture",
    sourceFile =
        "aws-spring-boot/src/consumerFixture/kotlin/io/bluetape4k/aws/spring/modulith/consumer/" +
            "AwsModulithForbiddenConfigurationExceptionConstructionFixture.kt",
    outputPath = "consumer-fixtures/aws-spring-modulith/forbidden-configuration/classes",
)

val compileAwsSpringModulithForbiddenDispatchConstructorFixture = registerSpringModulithConsumerFixtureCompile(
    name = "compileAwsSpringModulithForbiddenDispatchConstructorFixture",
    sourceFile =
        "aws-spring-boot/src/consumerFixture/kotlin/io/bluetape4k/aws/spring/modulith/consumer/" +
            "AwsModulithForbiddenDispatchExceptionConstructionFixture.kt",
    outputPath = "consumer-fixtures/aws-spring-modulith/forbidden-dispatch/classes",
)

fun registerBedrockConsumerFixtureCompile(
    name: String,
    sourcePath: String,
    classpath: Configuration,
    outputPath: String,
    moduleJarTask: String,
): TaskProvider<out Task> {
    val sourceSetName = name.removePrefix("compile").replaceFirstChar(Char::lowercase)
    val sourceSet: SourceSet = sourceSets.create(sourceSetName)
    tasks.named<JavaCompile>(sourceSet.compileJavaTaskName) {
        options.release.set(25)
    }
    val kotlinCompile = tasks.named<KotlinJvmCompile>(sourceSet.getCompileTaskName("kotlin")) {
        source(fileTree(sourcePath) { include("**/*Bedrock*.kt") })
        libraries.setFrom(classpath)
        destinationDirectory.set(layout.buildDirectory.dir(outputPath))
        compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
        dependsOn(moduleJarTask)
    }
    return tasks.register(name) {
        description = "Compiles a minimal external Bedrock consumer."
        group = "verification"
        dependsOn(kotlinCompile)
    }
}

fun registerAwsServiceConsumerFixtureCompile(
    name: String,
    sourceFile: String,
    classpath: Configuration,
    outputPath: String,
    moduleJarTask: String,
): TaskProvider<out Task> {
    val sourceSetName = name.removePrefix("compile").replaceFirstChar(Char::lowercase)
    val sourceSet: SourceSet = sourceSets.create(sourceSetName)
    tasks.named<JavaCompile>(sourceSet.compileJavaTaskName) {
        options.release.set(25)
    }
    val kotlinCompile = tasks.named<KotlinJvmCompile>(sourceSet.getCompileTaskName("kotlin")) {
        source(file(sourceFile))
        inputs.property("consumerFixtureOmit", omittedConsumerFixtureService ?: "")
        libraries.setFrom(classpath)
        destinationDirectory.set(layout.buildDirectory.dir(outputPath))
        compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
        dependsOn(moduleJarTask)
    }
    return tasks.register(name) {
        description = "Compiles the AWS Java/Kotlin service consumer fixture matrix locally without emulator or network access."
        group = "verification"
        dependsOn(kotlinCompile)
    }
}

val compileBedrockJavaConsumerFixture = registerBedrockConsumerFixtureCompile(
    "compileBedrockJavaConsumerFixture",
    "aws-java/src/consumerFixture/kotlin",
    bedrockJavaConsumerFixtureClasspath,
    "consumer-fixtures/aws-java-bedrock/classes",
    ":bluetape4k-aws-java:jar",
)
val compileBedrockKotlinConsumerFixture = registerBedrockConsumerFixtureCompile(
    "compileBedrockKotlinConsumerFixture",
    "aws-kotlin/src/consumerFixture/kotlin",
    bedrockKotlinConsumerFixtureClasspath,
    "consumer-fixtures/aws-kotlin-bedrock/classes",
    ":bluetape4k-aws-kotlin:jar",
)

val compileAwsJavaServiceConsumerFixture = registerAwsServiceConsumerFixtureCompile(
    "compileAwsJavaServiceConsumerFixture",
    "aws-java/src/consumerFixture/kotlin/io/bluetape4k/aws/consumer/JavaServiceConsumerFixture.kt",
    awsJavaServiceConsumerFixtureClasspath,
    "consumer-fixtures/aws-java-services/classes",
    ":bluetape4k-aws-java:jar",
)
val compileAwsKotlinServiceConsumerFixture = registerAwsServiceConsumerFixtureCompile(
    "compileAwsKotlinServiceConsumerFixture",
    "aws-kotlin/src/consumerFixture/kotlin/io/bluetape4k/aws/kotlin/consumer/KotlinServiceConsumerFixture.kt",
    awsKotlinServiceConsumerFixtureClasspath,
    "consumer-fixtures/aws-kotlin-services/classes",
    ":bluetape4k-aws-kotlin:jar",
)

val kinesisDryRunFixtureRoot = layout.projectDirectory.dir("src/abi-fixtures/kinesis-dry-run-pre-change")
val kinesisDryRunStubClasses = layout.buildDirectory.dir("consumer-fixtures/kinesis-dry-run/stub-classes")
val kinesisDryRunConsumerClasses = layout.buildDirectory.dir("consumer-fixtures/kinesis-dry-run/consumer-classes")
val awsKotlinProductionJar = layout.projectDirectory.file(
    "aws-kotlin/build/libs/bluetape4k-aws-kotlin-${baseVersion + snapshotVersion}.jar",
)

fun requireClasspathExcludes(
    label: String,
    classpath: FileCollection,
    forbidden: File,
) {
    val forbiddenPath = forbidden.canonicalFile
    require(classpath.files.none { it.canonicalFile == forbiddenPath }) {
        "$label must not contain ${forbiddenPath.path}"
    }
}

val verifyKinesisDryRunFixtureIntegrity = tasks.register<VerifyKinesisDryRunFixtureIntegrityTask>(
    "verifyKinesisDryRunFixtureIntegrity",
) {
    description = "Fails closed when the frozen pre-change Kinesis ABI fixture drifts."
    group = "verification"
    fixtureRoot.set(kinesisDryRunFixtureRoot)
    fixtureFiles.from(
        fileTree(kinesisDryRunFixtureRoot) {
            include("*.javap.txt")
            include("stub/**/*.java")
        },
    )
    expectedSha256.set(
        mapOf(
            "get-shard-iterator.javap.txt" to "04fd9a7f6a23a9047c5b478cb02a1ad771bd2e80c7dde108e9c82e6a47ad6352",
            "kinesis-client-extensions.javap.txt" to "4b65d04ce34c9c3facd02fafcd27a87c3652472300043391dd2e62b1713a9f21",
            "put-record.javap.txt" to "c90e637ccec342d3fa3e22ddc3d04f902bf91d01994df1ad34908e6a0f6a5392",
            "stub/io/bluetape4k/aws/kotlin/kinesis/KinesisClientExtensionsKt.java" to
                "0aa1107bbe117de09b39658a9d428bf9a3225325b2b6f1a57dfbe507f9c56d94",
            "stub/io/bluetape4k/aws/kotlin/kinesis/model/GetShardIteratorKt.java" to
                "82ea01abd702e78c3bad9822d88c7767b5cf75b5e7c85f923316849cfba25fc0",
            "stub/io/bluetape4k/aws/kotlin/kinesis/model/PutRecordKt.java" to
                "79a4459e26d8b7ada73360fae42dd939d88542bb29dbf67f190e50c9a6a9faaf",
        ),
    )
    reportFile.set(layout.buildDirectory.file("reports/abi/issue-620/kinesis-dry-run-fixture-integrity.json"))
}

val compileKinesisDryRunLegacyStubs = tasks.register<JavaCompile>("compileKinesisDryRunLegacyStubs") {
    description = "Compiles the pre-change Kinesis JVM surface without the production JAR."
    group = "verification"
    dependsOn(verifyKinesisDryRunFixtureIntegrity)
    source(fileTree(kinesisDryRunFixtureRoot.dir("stub")) { include("**/*.java") })
    classpath = kinesisDryRunLegacyDependencies
    destinationDirectory.set(kinesisDryRunStubClasses)
    options.release.set(25)
    options.encoding = "UTF-8"
    doFirst {
        requireClasspathExcludes("Kinesis legacy stub compile classpath", classpath, awsKotlinProductionJar.asFile)
    }
}

val compileKinesisDryRunLegacyConsumer = tasks.register<JavaCompile>("compileKinesisDryRunLegacyConsumer") {
    description = "Compiles a legacy Kinesis consumer against stubs instead of the production JAR."
    group = "verification"
    dependsOn(compileKinesisDryRunLegacyStubs)
    source(fileTree(kinesisDryRunFixtureRoot.dir("consumer")) { include("**/*.java") })
    classpath = files(kinesisDryRunStubClasses) + kinesisDryRunLegacyDependencies
    destinationDirectory.set(kinesisDryRunConsumerClasses)
    options.release.set(25)
    options.encoding = "UTF-8"
    doFirst {
        requireClasspathExcludes("Kinesis legacy consumer compile classpath", classpath, awsKotlinProductionJar.asFile)
        require(classpath.files.any { it.canonicalFile == kinesisDryRunStubClasses.get().asFile.canonicalFile }) {
            "Kinesis legacy consumer compile classpath must contain the pre-change stub output"
        }
    }
}

val verifyKinesisDryRunAdditiveAbi = tasks.register<VerifyAdditiveKinesisAbiTask>(
    "verifyKinesisDryRunAdditiveAbi",
) {
    description = "Verifies all 12 pre-change Kinesis JVM methods remain in the production JAR."
    group = "verification"
    dependsOn(verifyKinesisDryRunFixtureIntegrity, ":bluetape4k-aws-kotlin:jar")
    productionJar.set(awsKotlinProductionJar)
    baselineFiles.from(
        kinesisDryRunFixtureRoot.file("kinesis-client-extensions.javap.txt"),
        kinesisDryRunFixtureRoot.file("put-record.javap.txt"),
        kinesisDryRunFixtureRoot.file("get-shard-iterator.javap.txt"),
    )
    reportFile.set(layout.buildDirectory.file("reports/abi/issue-620/kinesis-dry-run-additive.json"))
}

val verifyKinesisDryRunLegacyInvocations = tasks.register("verifyKinesisDryRunLegacyInvocations") {
    description = "Verifies the isolated legacy consumer references all 12 exact pre-change JVM descriptors."
    group = "verification"
    dependsOn(compileKinesisDryRunLegacyConsumer)
    val baselineFiles = files(
        kinesisDryRunFixtureRoot.file("kinesis-client-extensions.javap.txt"),
        kinesisDryRunFixtureRoot.file("put-record.javap.txt"),
        kinesisDryRunFixtureRoot.file("get-shard-iterator.javap.txt"),
    )
    inputs.files(baselineFiles)
    inputs.dir(kinesisDryRunConsumerClasses)
    doLast {
        val expected = baselineFiles.files.sortedBy(File::getName).flatMap { fixture ->
            val lines = fixture.readLines()
            val owner = lines.single { it.startsWith("CLASS ") }
                .removePrefix("CLASS ")
                .replace('.', '/')
            lines.mapIndexedNotNull { index, line ->
                if (!line.startsWith("public static ")) {
                    null
                } else {
                    val method = Regex(" ([^ (]+)\\(").find(line)?.groupValues?.get(1)
                        ?: error("Cannot parse method from ${fixture.path}:${index + 1}")
                    val descriptor = lines.getOrNull(index + 1)?.removePrefix("descriptor: ")?.trim()
                        ?: error("Missing descriptor after ${fixture.path}:${index + 1}")
                    KinesisLegacyReference(owner, method, descriptor)
                }
            }
        }
        require(expected.size == 12) { "Expected exactly 12 legacy Kinesis references, found ${expected.size}" }

        val outputDirectory = kinesisDryRunConsumerClasses.get().asFile
        val javap = ProcessBuilder(
            "javap",
            "-classpath",
            outputDirectory.absolutePath,
            "-p",
            "-c",
            "-s",
            "io.bluetape4k.aws.kotlin.kinesis.KinesisDryRunLegacyConsumer",
        ).redirectErrorStream(true).start()
        val output = javap.inputStream.bufferedReader().use { it.readText() }
        require(javap.waitFor() == 0) { "javap failed for isolated Kinesis legacy consumer: $output" }
        expected.forEach { reference ->
            val token = "Method ${reference.owner}.${reference.method}:${reference.descriptor}"
            require(token in output) { "Missing exact legacy invocation: $token" }
        }
    }
}

val runKinesisDryRunLegacyConsumer = tasks.register<JavaExec>("runKinesisDryRunLegacyConsumer") {
    description = "Links and runs the isolated pre-change Kinesis consumer against the production JAR."
    group = "verification"
    dependsOn(compileKinesisDryRunLegacyConsumer, ":bluetape4k-aws-kotlin:jar")
    mainClass.set("io.bluetape4k.aws.kotlin.kinesis.KinesisDryRunLegacyConsumer")
    classpath = files(kinesisDryRunConsumerClasses, awsKotlinProductionJar) + kinesisDryRunLegacyDependencies
    doFirst {
        val stubOutput = kinesisDryRunStubClasses.get().asFile.canonicalFile
        require(classpath.files.none { it.canonicalFile == stubOutput }) {
            "Kinesis legacy runtime classpath must not contain the pre-change stub output"
        }
        require(classpath.files.any { it.canonicalFile == awsKotlinProductionJar.asFile.canonicalFile }) {
            "Kinesis legacy runtime classpath must contain the production JAR"
        }
    }
}

val verifyAwsConsumerFixturePublication = tasks.register("verifyAwsConsumerFixturePublication") {
    description = "Verifies AWS service SDKs remain compileOnly in generated Java/Kotlin publication metadata."
    group = "verification"
    dependsOn(
        ":bluetape4k-aws-java:generatePomFileForBluetapeAwsPublication",
        ":bluetape4k-aws-java:generateMetadataFileForBluetapeAwsPublication",
        ":bluetape4k-aws-kotlin:generatePomFileForBluetapeAwsPublication",
        ":bluetape4k-aws-kotlin:generateMetadataFileForBluetapeAwsPublication",
    )
    doLast {
        val publicationFiles = listOf(
            file("aws-java/build/publications/BluetapeAws/pom-default.xml"),
            file("aws-java/build/publications/BluetapeAws/module.json"),
            file("aws-kotlin/build/publications/BluetapeAws/pom-default.xml"),
            file("aws-kotlin/build/publications/BluetapeAws/module.json"),
        )
        val forbiddenDependencies = listOf(
            "software.amazon.awssdk" to listOf(
                "s3",
                "s3tables",
                "dynamodb-enhanced",
                "sns",
                "sqs",
                "kms",
                "ses",
                "cloudwatch",
                "kinesis",
                "scheduler",
                "sfn",
                "lambda",
                "sts",
            ),
            "aws.sdk.kotlin" to listOf(
                "s3",
                "s3tables",
                "dynamodb",
                "sns",
                "sqs",
                "kms",
                "ses",
                "cloudwatch",
                "kinesis",
                "scheduler",
                "sfn",
                "lambda",
                "sts",
            ),
        )

        fun containsPublishedDependency(text: String, group: String, module: String): Boolean {
            val pomDependencies = text
                .substringAfter("</dependencyManagement>", "")
                .substringAfter("<dependencies>", "")
                .substringBefore("</dependencies>", "")
            val pomPattern = Regex(
                "<groupId>${Regex.escape(group)}</groupId>\\s*<artifactId>${Regex.escape(module)}</artifactId>",
            )
            if (pomPattern.containsMatchIn(pomDependencies)) {
                return true
            }

            val metadataDependencyArrays = Regex(
                "\\\"dependencies\\\"\\s*:\\s*\\[(.*?)]",
                setOf(RegexOption.DOT_MATCHES_ALL),
            ).findAll(text).map { it.groupValues[1] }
            val metadataPattern = Regex(
                "\\\"group\\\"\\s*:\\s*\\\"${Regex.escape(group)}\\\"\\s*,\\s*\\\"module\\\"\\s*:\\s*\\\"${Regex.escape(module)}\\\"",
            )
            return metadataDependencyArrays.any(metadataPattern::containsMatchIn)
        }

        publicationFiles.forEach { publicationFile ->
            require(publicationFile.isFile) {
                "Publication metadata was not generated: ${publicationFile.path}"
            }
        }
        val leaks = publicationFiles.flatMap { publicationFile ->
            val text = publicationFile.readText()
            forbiddenDependencies.flatMap { (group, modules) ->
                modules.filter { module -> containsPublishedDependency(text, group, module) }
                    .map { module -> "${publicationFile.path}:$group:$module" }
            }
        }
        require(leaks.isEmpty()) {
            "AWS service SDKs must remain compileOnly, but publication metadata contains: ${leaks.joinToString()}"
        }
    }
}

data class LegacyAbiFixture(
    val taskName: String,
    val modulePath: String,
    val moduleDirectory: String,
    val className: String,
    val sourcePath: String,
    val fixturePath: String,
    val classEntry: String,
)

fun registerLegacyAbiVerification(
    fixture: LegacyAbiFixture,
    taskName: String = fixture.taskName,
    enforceImplementationBaseline: Boolean = false,
): TaskProvider<VerifyLegacyAbiTask> =
    tasks.register<VerifyLegacyAbiTask>(taskName) {
        description = "Verifies the Issue #455 legacy ABI fixture for ${fixture.className}."
        group = "verification"
        dependsOn("${fixture.modulePath}:jar")
        moduleDirectory.set(fixture.moduleDirectory)
        className.set(fixture.className)
        classEntry.set(fixture.classEntry)
        fixturePath.set(fixture.fixturePath)
        repositoryDirectory.set(layout.projectDirectory)
        sourceFile.set(layout.projectDirectory.file(fixture.sourcePath))
        fixtureDirectory.set(layout.projectDirectory.dir(fixture.fixturePath))
        this.enforceImplementationBaseline.set(enforceImplementationBaseline)
        reportFile.set(layout.buildDirectory.file("reports/abi/issue-455/$taskName.json"))
    }

val verifySqsExtendedLegacyAbi = registerLegacyAbiVerification(
    LegacyAbiFixture(
        taskName = "verifySqsExtendedLegacyAbi",
        modulePath = ":bluetape4k-aws-spring-boot",
        moduleDirectory = "aws-spring-boot",
        className = "io.bluetape4k.aws.spring.sqs.SqsOperations",
        sourcePath = "aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsOperations.kt",
        fixturePath = "src/abi-fixtures/sqs-pre-change",
        classEntry = "io/bluetape4k/aws/spring/sqs/SqsOperations.class",
    ),
)
val verifyS3ExtendedLegacyAbi = registerLegacyAbiVerification(
    LegacyAbiFixture(
        taskName = "verifyS3ExtendedLegacyAbi",
        modulePath = ":bluetape4k-aws-spring-boot",
        moduleDirectory = "aws-spring-boot",
        className = "io.bluetape4k.aws.spring.s3.S3Operations",
        sourcePath = "aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3Operations.kt",
        fixturePath = "src/abi-fixtures/s3-pre-change",
        classEntry = "io/bluetape4k/aws/spring/s3/S3Operations.class",
    ),
)

val verifySqsLegacyImplementationBaseline = registerLegacyAbiVerification(
    LegacyAbiFixture(
        taskName = "verifySqsExtendedLegacyAbi",
        modulePath = ":bluetape4k-aws-spring-boot",
        moduleDirectory = "aws-spring-boot",
        className = "io.bluetape4k.aws.spring.sqs.SqsOperations",
        sourcePath = "aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsOperations.kt",
        fixturePath = "src/abi-fixtures/sqs-pre-change",
        classEntry = "io/bluetape4k/aws/spring/sqs/SqsOperations.class",
    ),
    taskName = "verifySqsLegacyImplementationBaseline",
    enforceImplementationBaseline = true,
)
val verifyS3LegacyImplementationBaseline = registerLegacyAbiVerification(
    LegacyAbiFixture(
        taskName = "verifyS3ExtendedLegacyAbi",
        modulePath = ":bluetape4k-aws-spring-boot",
        moduleDirectory = "aws-spring-boot",
        className = "io.bluetape4k.aws.spring.s3.S3Operations",
        sourcePath = "aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3Operations.kt",
        fixturePath = "src/abi-fixtures/s3-pre-change",
        classEntry = "io/bluetape4k/aws/spring/s3/S3Operations.class",
    ),
    taskName = "verifyS3LegacyImplementationBaseline",
    enforceImplementationBaseline = true,
)

val implementationBaselineCheck = tasks.register("implementationBaselineCheck") {
    description = "Audits source and bytecode implementation baselines separately from the public ABI gate."
    group = "verification"
    dependsOn(verifySqsLegacyImplementationBaseline, verifyS3LegacyImplementationBaseline)
}

val compatibilityCheck = tasks.register<WriteCompatibilityReportTask>("compatibilityCheck") {
    description = "Runs the public ABI, legacy consumer, and optional SDK compatibility gate."
    group = "verification"
    checks.set(
        listOf(
            "verifySqsExtendedLegacyAbi",
            "verifyS3ExtendedLegacyAbi",
            ":bluetape4k-aws-spring-boot:compatibilityTest",
            "compileSqsOperationsLegacyConsumerFixture",
            "compileSqsPropertiesLegacyConsumerFixture",
            "compileSqsListenerAnnotationLegacyConsumerFixture",
            "compileSqsListenerInterceptorLegacyConsumerFixture",
            "compileSqsBatchConsumerFixture",
            "compileSnsOperationsLegacyConsumerFixture",
            "compileAwsSpringModulithConsumerFixture",
            "verifyKinesisDryRunAdditiveAbi",
            "verifyKinesisDryRunFixtureIntegrity",
            "verifyKinesisDryRunLegacyInvocations",
            "runKinesisDryRunLegacyConsumer",
            "compileAwsKotlinServiceConsumerFixture",
        ),
    )
    dependsOn(
        verifySqsExtendedLegacyAbi,
        verifyS3ExtendedLegacyAbi,
        ":bluetape4k-aws-spring-boot:compatibilityTest",
        compileSqsOperationsLegacyConsumerFixture,
        compileSqsPropertiesLegacyConsumerFixture,
        compileSqsListenerAnnotationLegacyConsumerFixture,
        compileSqsListenerInterceptorLegacyConsumerFixture,
        compileSqsBatchConsumerFixture,
        compileSnsOperationsLegacyConsumerFixture,
        compileAwsSpringModulithConsumerFixture,
        verifyKinesisDryRunAdditiveAbi,
        verifyKinesisDryRunFixtureIntegrity,
        verifyKinesisDryRunLegacyInvocations,
        runKinesisDryRunLegacyConsumer,
        compileAwsKotlinServiceConsumerFixture,
    )
    reportFile.set(layout.buildDirectory.file("reports/compatibility/compatibility-check.json"))
}

tasks.named("check") {
    dependsOn(compileAwsKtorSqsConsumerFixture)
    dependsOn(compileBedrockJavaConsumerFixture)
    dependsOn(compileBedrockKotlinConsumerFixture)
    dependsOn(compileAwsJavaServiceConsumerFixture)
    dependsOn(compileAwsKotlinServiceConsumerFixture)
    dependsOn(verifyAwsConsumerFixturePublication)
    dependsOn(compatibilityCheck)
}

allprojects {
    group = projectGroup
    version = baseVersion + snapshotVersion

    repositories {
        mavenCentral()
        maven {
            name = "central-snapshots"
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        }
    }
    configurations.all {
        resolutionStrategy.cacheChangingModulesFor(1, TimeUnit.DAYS)
    }
}

subprojects {
    val javaCompatibilityVersion = 25
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(javaCompatibilityVersion)
    }
    if (!path.contains("examples")) {
        apply(plugin = "com.gradleup.nmcp")
    }

    configurations.matching { it.name.startsWith("nmcp") }.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlinx" && requested.name.startsWith("kotlinx-serialization")) {
                useVersion("1.9.0")
                because("nmcp runtime compatibility")
            }
        }
    }

    plugins.withId("com.gradleup.nmcp") {
        extensions.configure<NmcpExtension>("nmcp") {
            publishAllPublicationsToCentralPortal {
                username.set(centralUser)
                password.set(centralPassword)
                publishingType.set("AUTOMATIC")
                uploadSnapshotsParallelism.set(centralSnapshotsParallelism)
            }
        }
    }
}

subprojects {
    if (shouldApplyNative && name.startsWith("aws-spring-boot-") && name.endsWith("-examples")) {
        pluginManager.withPlugin("org.springframework.boot") {
            pluginManager.apply("org.graalvm.buildtools.native")
        }
    }

    // BOM 모듈은 java-platform 플러그인을 사용하므로 Java/Kotlin 설정을 건너뜁니다.
    if (name == "bluetape4k-aws-bom") return@subprojects

    apply {
        plugin<JavaLibraryPlugin>()
        plugin("org.jetbrains.kotlin.jvm")
        plugin("org.jetbrains.kotlinx.atomicfu")
        plugin("maven-publish")
        plugin("signing")
        plugin("io.spring.dependency-management")
        plugin("org.jetbrains.dokka")
        plugin("com.adarshr.test-logger")
        if (shouldApplyDetekt && !path.contains("examples")) {
            plugin("dev.detekt")
        }
        if (shouldApplyKover) {
            plugin("org.jetbrains.kotlinx.kover")
        }
    }

    pluginManager.withPlugin("dev.detekt") {
        extensions.configure<DetektExtension>("detekt") {
            baseline.set(rootProject.layout.projectDirectory.file("config/detekt/baseline-${project.name}.xml"))
        }
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val kotlinCompatibilityVersion = 25
        val kotlinJvmTarget = JvmTarget.JVM_25
        kotlin {
            jvmToolchain(kotlinCompatibilityVersion)
            compilerOptions {
                languageVersion.set(KotlinVersion.KOTLIN_2_4)
                apiVersion.set(KotlinVersion.KOTLIN_2_4)
                jvmTarget.set(kotlinJvmTarget)
                freeCompilerArgs = listOf(
                    "-Xjsr305=strict",
                    "-jvm-default=enable",
                    "-Xstring-concat=indy",
                )
                val experimentalAnnotations = listOf(
                    "kotlin.RequiresOptIn",
                    "kotlin.ExperimentalStdlibApi",
                    "kotlin.contracts.ExperimentalContracts",
                    "kotlin.experimental.ExperimentalTypeInference",
                    "kotlinx.coroutines.ExperimentalCoroutinesApi",
                    "kotlinx.coroutines.InternalCoroutinesApi",
                    "kotlinx.coroutines.FlowPreview",
                    "kotlinx.coroutines.DelicateCoroutinesApi",
                )
                freeCompilerArgs.addAll(experimentalAnnotations.map { "-opt-in=$it" })
            }
        }
    }

    pluginManager.withPlugin("org.jetbrains.kotlinx.atomicfu") {
        atomicfu {
            transformJvm = true
            jvmVariant = "VH"
        }
    }

    tasks {
        abstract class SigningMutexService: BuildService<BuildServiceParameters.None>

        val signingMutex = gradle.sharedServices.registerIfAbsent("signing-mutex", SigningMutexService::class) {
            maxParallelUsages.set(1)
        }

        compileJava { options.isIncremental = true }
        compileKotlin { compilerOptions { incremental = true } }

        test {
            useJUnitPlatform()
            jvmArgs(
                "-Xshare:off",
                "-Xms2M",
                "-Xmx4G",
                "-XX:+UseG1GC",
                "-XX:+UnlockExperimentalVMOptions",
                "-XX:+EnableDynamicAgentLoading",
                "--enable-preview",
                "-Didea.io.use.nio2=true"
            )
            testLogging {
                showExceptions = true
                showCauses = true
                showStackTraces = true
                events("failed")
            }
        }

        withType<Sign>().configureEach {
            usesService(signingMutex)
        }

        testlogger {
            theme = com.adarshr.gradle.testlogger.theme.ThemeType.MOCHA_PARALLEL
            showFullStackTraces = true
        }

        withType<Detekt>().configureEach detekt@{
            reports.checkstyle.required.set(true)
            detektReportMerge.configure {
                dependsOn(this@detekt)
                input.from(this@detekt.reports.checkstyle.outputLocation)
            }
            verifyDetektCoverage.configure {
                moduleReports.from(this@detekt.reports.checkstyle.outputLocation)
            }
        }

        jar {
            manifest.attributes["Specification-Title"] = project.name
            manifest.attributes["Specification-Version"] = project.version
            manifest.attributes["Implementation-Title"] = project.name
            manifest.attributes["Implementation-Version"] = project.version
            manifest.attributes["Automatic-Module-Name"] = project.name.replace('-', '.')
            manifest.attributes["Created-By"] =
                "${System.getProperty("java.version")} (${System.getProperty("java.specification.vendor")})"
        }

        dokka {
            dokkaPublications.html {
                outputDirectory.set(layout.buildDirectory.dir("javadoc"))
            }
            dokkaSourceSets.configureEach {
                val dokkaModuleDoc = project.layout.projectDirectory.file("DOKKA.md").asFile
                includes.from(project.files(if (dokkaModuleDoc.exists()) "DOKKA.md" else "README.md"))
            }
        }

        clean {
            doLast {
                delete("./.project")
                delete("./out")
                delete("./bin")
            }
        }
    }

    dependencyManagement {
        setApplyMavenExclusions(false)
        imports {
            mavenBom(bt4kLibrary("bluetape4k-bom").get().toString())
            mavenBom("org.jetbrains.kotlinx:kotlinx-coroutines-bom:${bt4kVersion("kotlinx-coroutines")}")
            mavenBom("org.jetbrains.kotlin:kotlin-bom:${bt4kVersion("kotlin")}")
            mavenBom(rootBt4k.junit.bom.get().toString())
            mavenBom("org.testcontainers:testcontainers-bom:${bt4kVersion("testcontainers")}")
            mavenBom(bt4kLibrary("aws2-bom").get().toString())
        }

        dependencies {

            // <central-catalog-local-aliases>

            dependency("aws.sdk.kotlin:aws-config:${bt4kVersion("aws-kotlin")}")

            dependency("aws.sdk.kotlin:aws-endpoint:${bt4kVersion("aws-kotlin")}")

            dependency("aws.sdk.kotlin:aws-http:${bt4kVersion("aws-kotlin")}")

            dependency("aws.sdk.kotlin:bedrockruntime:${bt4kVersion("aws-kotlin")}")

            dependency("aws.sdk.kotlin:cloudwatch:${bt4kVersion("aws-kotlin")}")

            dependency("aws.sdk.kotlin:cloudwatchlogs:${bt4kVersion("aws-kotlin")}")

            dependency("aws.sdk.kotlin:dynamodb:${bt4kVersion("aws-kotlin")}")

            dependency("aws.sdk.kotlin:dynamodbstreams:${bt4kVersion("aws-kotlin")}")

            dependency("aws.sdk.kotlin:eventbridge:${bt4kVersion("aws-kotlin")}")

            dependency("aws.sdk.kotlin:http-client-engine-crt:${bt4kVersion("aws-kotlin")}")

            dependency("aws.sdk.kotlin:lambda:${bt4kVersion("aws-kotlin")}")

            dependency("aws.sdk.kotlin:kinesis:${bt4kVersion("aws-kotlin")}")

            dependency("aws.sdk.kotlin:kms:${bt4kVersion("aws-kotlin")}")

            dependency("aws.sdk.kotlin:s3:${bt4kVersion("aws-kotlin")}")

            dependency("aws.sdk.kotlin:s3tables:${bt4kVersion("aws-kotlin")}")

            dependency("aws.sdk.kotlin:scheduler:${bt4kVersion("aws-kotlin")}")

            dependency("aws.sdk.kotlin:sfn:${bt4kVersion("aws-kotlin")}")

            dependency("aws.sdk.kotlin:secretsmanager:${bt4kVersion("aws-kotlin")}")

            dependency("aws.sdk.kotlin:ses:${bt4kVersion("aws-kotlin")}")

            dependency("aws.sdk.kotlin:sesv2:${bt4kVersion("aws-kotlin")}")

            dependency("aws.sdk.kotlin:sns:${bt4kVersion("aws-kotlin")}")

            dependency("aws.sdk.kotlin:sqs:${bt4kVersion("aws-kotlin")}")

            dependency("aws.sdk.kotlin:ssm:${bt4kVersion("aws-kotlin")}")

            dependency("aws.sdk.kotlin:sts:${bt4kVersion("aws-kotlin")}")

            dependency("io.ktor:ktor-client-cio:${bt4kVersion("ktor")}")

            dependency("io.ktor:ktor-client-content-negotiation:${bt4kVersion("ktor")}")

            dependency("io.ktor:ktor-client-core:${bt4kVersion("ktor")}")

            dependency("io.ktor:ktor-client-mock:${bt4kVersion("ktor")}")

            dependency("io.ktor:ktor-serialization-jackson:${bt4kVersion("ktor")}")

            dependency("io.ktor:ktor-server-cio:${bt4kVersion("ktor")}")

            dependency("io.ktor:ktor-server-content-negotiation:${bt4kVersion("ktor")}")

            dependency("io.ktor:ktor-server-core:${bt4kVersion("ktor")}")

            dependency("io.ktor:ktor-server-netty:${bt4kVersion("ktor")}")

            dependency("io.ktor:ktor-server-test-host:${bt4kVersion("ktor")}")

            dependency("org.awaitility:awaitility-kotlin:${bt4kVersion("awaitility")}")

            dependency("org.jetbrains.exposed:exposed-bom:${bt4kVersion("exposed")}")

            dependency("org.jetbrains.kotlin:kotlin-bom:${bt4kVersion("kotlin")}")

            dependency("org.jetbrains.kotlinx:kotlinx-coroutines-bom:${bt4kVersion("kotlinx-coroutines")}")

            dependency("org.slf4j:jcl-over-slf4j:${bt4kVersion("slf4j")}")

            dependency("org.slf4j:jul-to-slf4j:${bt4kVersion("slf4j")}")

            dependency("org.slf4j:log4j-over-slf4j:${bt4kVersion("slf4j")}")

            dependency("org.springframework.boot:spring-boot-dependencies:${bt4kVersion("spring-boot")}")

            dependency("org.testcontainers:testcontainers-bom:${bt4kVersion("testcontainers")}")

            dependency("org.testcontainers:testcontainers-junit-jupiter:${bt4kVersion("testcontainers")}")

            dependency("org.testcontainers:testcontainers-localstack:${bt4kVersion("testcontainers")}")

            dependency("org.testcontainers:testcontainers-postgresql:${bt4kVersion("testcontainers")}")

            dependency("software.amazon.awssdk:apache-client:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:auth:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:aws-core:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:aws-crt-client:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:bedrockruntime:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:cloudwatch:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:cloudwatchlogs:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:dynamodb-enhanced:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:ec2:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:eventbridge:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:imds:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:kinesis:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:lambda:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:kms:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:netty-nio-client:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:rds:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:s3:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:s3-transfer-manager:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:s3control:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:s3vectors:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:s3tables:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:scheduler:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:sdk-core:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:sfn:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:secretsmanager:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:ses:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:sesv2:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:sns:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:sqs:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:ssm:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:sts:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:test-utils:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:url-connection-client:${bt4kVersion("aws2")}")

            dependency("software.amazon.awssdk:utils:${bt4kVersion("aws2")}")

            // </central-catalog-local-aliases>
            dependency("org.postgresql:postgresql:${bt4kVersion("postgresql")}")
            dependency("org.slf4j:slf4j-api:${bt4kVersion("slf4j")}")
        }
    }

    dependencies {
        add("api", rootBt4k.jetbrains.annotations)

        add("implementation", rootLibs.kotlin.stdlib)
        add("implementation", rootLibs.kotlin.reflect)
        add("testImplementation", rootLibs.kotlin.test)
        add("testImplementation", rootLibs.kotlin.test.junit5)

        add("implementation", rootLibs.kotlinx.coroutines.core)
        add("implementation", rootBt4k.kotlinx.atomicfu)

        add("api", bt4kLibrary("slf4j-api"))
        add("testImplementation", rootBt4k.logback.asProvider())
        add("testImplementation", rootLibs.jcl.over.slf4j)
        add("testImplementation", rootLibs.jul.to.slf4j)
        add("testImplementation", rootLibs.log4j.over.slf4j)

        add("testImplementation", rootLibs.junit.jupiter)
        add("testRuntimeOnly", rootLibs.junit.platform.engine)

        add("testImplementation", rootLibs.awaitility.kotlin)
        add("testImplementation", rootBt4k.mockk)
    }

    publishing {
        publications {
            if (!project.path.contains("examples")) {
                create<MavenPublication>("BluetapeAws") {
                    val sourcesJar = tasks.register<Jar>("sourcesJar") {
                        archiveClassifier.set("sources")
                        from(sourceSets["main"].allSource)
                    }
                    val javadocJar = tasks.register<Jar>("javadocJar") {
                        archiveClassifier.set("javadoc")
                        from(layout.buildDirectory.dir("javadoc"))
                    }
                    from(components["java"])
                    artifact(sourcesJar)
                    artifact(javadocJar)

                    pom {
                        name.set(project.name)
                        description.set("Kotlin/JVM AWS SDK v2 and AWS Kotlin SDK wrappers with Spring Boot integration — part of the bluetape4k ecosystem")
                        url.set("https://github.com/bluetape4k/bluetape4k-aws")
                        licenses {
                            license {
                                name.set("The Apache License, Version 2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            }
                        }
                        developers {
                            developer {
                                id.set("debop")
                                name.set("Sunghyouk Bae")
                                email.set("sunghyouk.bae@gmail.com")
                            }
                        }
                        scm {
                            connection.set("scm:git:git://github.com/bluetape4k/bluetape4k-aws.git")
                            developerConnection.set("scm:git:ssh://github.com/bluetape4k/bluetape4k-aws.git")
                            url.set("https://github.com/bluetape4k/bluetape4k-aws")
                        }
                    }
                }
            }
        }
    }

    tasks.withType<GenerateMavenPom>().configureEach {
        notCompatibleWithConfigurationCache(
            "Spring dependency-management 1.1.7 configures Maven POM generation through a detached configuration that Gradle 9.7 cannot restore from the configuration cache.",
        )
    }

    configurePublishingSigning("BluetapeAws")
}

extensions.configure<NmcpAggregationExtension>("nmcpAggregation") {
    centralPortal {
        username.set(centralUser)
        password.set(centralPassword)
        publishingType.set("AUTOMATIC")
        uploadSnapshotsParallelism.set(centralSnapshotsParallelism)
    }
}

val manualModuleInventory = layout.buildDirectory.file("manual/module-inventory.json")

tasks.register("exportManualModuleInventory") {
    group = "documentation"
    description = "Exports the registered Gradle project inventory for manual validation."

    val repositoryRoot = project.rootDir.toPath()
    val modules = project.subprojects.sortedBy(Project::getPath).map { module ->
        val sourceDir = repositoryRoot.relativize(module.projectDir.toPath())
            .toString().replace(File.separatorChar, '/')
        val kind = if (sourceDir.startsWith("examples/")) "example" else "library"
        linkedMapOf(
            "gradlePath" to module.path,
            "projectName" to module.name,
            "sourceDir" to sourceDir,
            "kind" to kind,
        )
    }
    val inventoryJson = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(modules)) + "\n"
    inputs.property("inventoryJson", inventoryJson)
    outputs.file(manualModuleInventory)
    doLast {
        outputs.files.singleFile.apply {
            parentFile.mkdirs()
            writeText(inventoryJson)
        }
    }
}

dependencies {
    subprojects
        .filterNot { it.path.contains("examples") }
        .forEach { add("nmcpAggregation", rootDependencies.project(mapOf("path" to it.path))) }
}

if (shouldApplyKover) {
    dependencies {
        subprojects
            .filter { it.name != "bluetape4k-aws-bom" && !it.path.contains("examples") }
            .forEach { sub -> add("kover", rootDependencies.project(mapOf("path" to sub.path))) }
    }
}
