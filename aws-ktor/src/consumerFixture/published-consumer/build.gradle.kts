import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.GradleException
import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
    java
}

repositories {
    maven {
        url = uri(providers.gradleProperty("awsKtorConsumerRepository").get())
        metadataSources {
            gradleMetadata()
            mavenPom()
        }
    }
    mavenCentral()
}

val awsKtorConsumerClasspath = configurations.create("awsKtorConsumerClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
    }
}

dependencies {
    add("awsKtorConsumerClasspath", platform(providers.gradleProperty("awsKtorCentralBom").get()))
    add(
        "awsKtorConsumerClasspath",
        "${providers.gradleProperty("awsKtorGroup").get()}:bluetape4k-aws-ktor:${providers.gradleProperty("awsKtorVersion").get()}",
    )
}

tasks.register("resolveAwsKtorPublishedApi") {
    group = "verification"
    description = "Resolves API dependencies through the published aws-ktor Gradle module metadata."
    doLast {
        awsKtorConsumerClasspath.resolve()
        val modules = awsKtorConsumerClasspath.incoming.resolutionResult.allComponents
            .mapNotNull { it.id as? ModuleComponentIdentifier }
        val leakedJavaDependencies = modules.filter { id ->
            (id.group == "software.amazon.awssdk" && id.module != "bom") ||
                (id.group == "io.github.bluetape4k" && id.module == "bluetape4k-aws-java")
        }
        if (leakedJavaDependencies.isNotEmpty()) {
            throw GradleException(
                "Published aws-ktor consumer resolved AWS Java SDK service dependencies: $leakedJavaDependencies",
            )
        }
        val expectedServices = setOf(
            "cloudwatch",
            "cloudwatchlogs",
            "eventbridge",
            "kinesis",
            "dynamodb",
            "s3control",
            "s3vectors",
            "sesv2",
            "sns",
            "sqs",
            "sts",
        )
        val awsKotlinModules = modules.filter { it.group == "aws.sdk.kotlin" }
        val resolvedServices = awsKotlinModules.map { it.module }.toSet()
        val missing = expectedServices - resolvedServices
        val expectedVersion = providers.gradleProperty("awsKotlinVersion").get()
        val wrongVersions = awsKotlinModules.filter { it.version != expectedVersion }
        if (missing.isNotEmpty() || wrongVersions.isNotEmpty()) {
            throw GradleException(
                "Published aws-ktor consumer resolved missing AWS Kotlin services $missing " +
                    "or versions other than $expectedVersion: $wrongVersions",
            )
        }
        println("PUBLISHED_AWS_KTOR_CONSUMER_RESOLVED ${awsKotlinModules.size} AWS Kotlin modules at $expectedVersion")
    }
}
