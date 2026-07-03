import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.report.ReportMergeTask
import nmcp.NmcpAggregationExtension
import nmcp.NmcpExtension
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import java.util.concurrent.TimeUnit

plugins {
    base
    `maven-publish`
    signing
    alias(libs.plugins.kotlin.jvm)

    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.allopen) apply false
    alias(libs.plugins.kotlin.noarg) apply false
    alias(libs.plugins.kotlinx.atomicfu)

    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.dependency.management)

    alias(libs.plugins.dokka)
    alias(libs.plugins.test.logger)

    alias(libs.plugins.nmcp.aggregation)
    alias(libs.plugins.nmcp) apply false

    alias(libs.plugins.kover) apply false
    alias(libs.plugins.graalvm.native) apply false
    alias(bt4k.plugins.exposed.plugin) apply false
}

val rootLibs = libs
val rootDependencies = dependencies
val bt4kCatalog = extensions.getByType<org.gradle.api.artifacts.VersionCatalogsExtension>().named("bt4k")
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
val shouldApplyNative = requestedTaskNames.any { it.contains("native", ignoreCase = true) }

if (shouldApplyDetekt) {
    apply(plugin = "io.gitlab.arturbosch.detekt")
}

if (shouldApplyKover) {
    apply(plugin = "org.jetbrains.kotlinx.kover")
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
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
    }
}

dependencies {
    awsKtorSqsConsumerFixtureClasspath(project(":bluetape4k-aws-ktor"))
    awsKtorSqsConsumerFixtureClasspath(libs.ktor.server.core)
}

val compileAwsKtorSqsConsumerFixture = tasks.register<JavaCompile>("compileAwsKtorSqsConsumerFixture") {
    description = "Compiles a minimal external SQS consumer against aws-ktor API dependencies."
    group = "verification"
    source(fileTree("aws-ktor/src/consumerFixture/java") { include("**/*.java") })
    classpath = awsKtorSqsConsumerFixtureClasspath
    destinationDirectory.set(layout.buildDirectory.dir("consumer-fixtures/aws-ktor-sqs/classes"))
    sourceCompatibility = "21"
    targetCompatibility = "21"
    options.encoding = "UTF-8"
}

tasks.named("check") {
    dependsOn(compileAwsKtorSqsConsumerFixture)
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
        if (shouldApplyKover) {
            plugin("org.jetbrains.kotlinx.kover")
        }
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        kotlin {
            jvmToolchain(21)
            compilerOptions {
                languageVersion.set(KotlinVersion.KOTLIN_2_3)
                apiVersion.set(KotlinVersion.KOTLIN_2_3)
                freeCompilerArgs = listOf(
                    "-Xjsr305=strict",
                    "-jvm-default=enable",
                    "-Xstring-concat=indy",
                    "-Xcontext-parameters",
                    "-Xannotation-default-target=param-property"
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
        abstract class TestMutexService: BuildService<BuildServiceParameters.None>
        abstract class SigningMutexService: BuildService<BuildServiceParameters.None>

        val testMutex = gradle.sharedServices.registerIfAbsent("test-mutex", TestMutexService::class) {
            maxParallelUsages.set(1)
        }
        val signingMutex = gradle.sharedServices.registerIfAbsent("signing-mutex", SigningMutexService::class) {
            maxParallelUsages.set(1)
        }

        compileJava { options.isIncremental = true }
        compileKotlin { compilerOptions { incremental = true } }

        test {
            usesService(testMutex)
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

        val reportMerge = register<ReportMergeTask>("reportMerge") {
            output.set(rootProject.layout.buildDirectory.file("reports/detekt/merged.xml"))
        }
        withType<Detekt>().configureEach detekt@{
            finalizedBy(reportMerge)
            reportMerge.configure { input.from(this@detekt.xmlReportFile) }
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
            mavenBom(rootLibs.bluetape4k.bom.get().toString())
            mavenBom(rootLibs.kotlinx.coroutines.bom.get().toString())
            mavenBom(rootLibs.kotlin.bom.get().toString())
            mavenBom(rootLibs.junit.bom.get().toString())
            mavenBom(rootLibs.testcontainers.bom.get().toString())
            mavenBom(rootLibs.aws2.bom.get().toString())
        }
    
        dependencies {
            dependency("org.postgresql:postgresql:${bt4kVersion("postgresql")}")
            dependency("org.slf4j:slf4j-api:${bt4kVersion("slf4j")}")
        }
    }

    dependencies {
        add("api", rootLibs.jetbrains.annotations)

        add("implementation", rootLibs.kotlin.stdlib)
        add("implementation", rootLibs.kotlin.reflect)
        add("testImplementation", rootLibs.kotlin.test)
        add("testImplementation", rootLibs.kotlin.test.junit5)

        add("implementation", rootLibs.kotlinx.coroutines.core)
        add("implementation", rootLibs.kotlinx.atomicfu)

        add("api", rootLibs.slf4j.api)
        add("testImplementation", rootLibs.logback)
        add("testImplementation", rootLibs.jcl.over.slf4j)
        add("testImplementation", rootLibs.jul.to.slf4j)
        add("testImplementation", rootLibs.log4j.over.slf4j)

        add("testImplementation", rootLibs.junit.jupiter)
        add("testRuntimeOnly", rootLibs.junit.platform.engine)

        add("testImplementation", rootLibs.awaitility.kotlin)
        add("testImplementation", rootLibs.mockk)
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
