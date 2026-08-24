plugins {
    alias(bt4k.plugins.kotlin.spring)
    alias(bt4k.plugins.kotlin.noarg)
    alias(bt4k.plugins.spring.boot) apply false
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

noArg {
    annotation("org.springframework.boot.context.properties.ConfigurationProperties")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${bt4k.versions.spring.boot.get()}")
        // Override Spring Boot's kotlin.version=2.2.x back to the catalog Kotlin version
        mavenBom("org.jetbrains.kotlin:kotlin-bom:${bt4k.versions.kotlin.get()}")
        mavenBom("org.jetbrains.kotlinx:kotlinx-coroutines-bom:${bt4k.versions.kotlinx.coroutines.get()}")
    }
}

dependencies {
    // bluetape4k-aws modules
    api(project(":bluetape4k-aws-java"))
    compileOnly(project(":bluetape4k-aws-exposed"))
    compileOnly(project(":bluetape4k-aws-kotlin"))
    compileOnly(bt4k.aws.dax.client)
    compileOnly(libs.aws2.cloudwatch)
    compileOnly(libs.aws2.cloudwatchlogs)
    compileOnly(libs.aws2.dynamodb.enhanced)
    compileOnly(libs.aws2.eventbridge)
    compileOnly(libs.aws2.appconfigdata)
    compileOnly(libs.aws2.imds)
    compileOnly(libs.aws2.kinesis)
    compileOnly(libs.aws2.kms)
    compileOnly(libs.aws2.s3)
    compileOnly(libs.aws2.s3control)
    compileOnly(libs.aws2.s3vectors)
    compileOnly(libs.aws2.s3.transfer.manager)
    compileOnly(libs.aws2.secretsmanager)
    compileOnly(libs.aws2.sesv2)
    compileOnly(libs.aws2.sns)
    compileOnly(libs.aws2.sns.message.manager)
    compileOnly(libs.aws2.sqs)
    compileOnly(libs.aws2.sts)
    compileOnly(libs.aws2.ssm)

    // bluetape4k artifacts
    api(bt4k.bluetape4k.io)
    api(bt4k.bluetape4k.coroutines)
    api(libs.micrometer.core)
    compileOnly(libs.micrometer.registry.cloudwatch2)
    compileOnly(bt4k.bluetape4k.jackson3)
    compileOnly(bt4k.bluetape4k.testcontainers)
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(project(":bluetape4k-aws-exposed"))
    testImplementation(bt4k.aws.dax.client)
    testImplementation(libs.aws2.cloudwatch)
    testImplementation(libs.aws2.cloudwatchlogs)
    testImplementation(libs.aws2.dynamodb.enhanced)
    testImplementation(libs.aws2.eventbridge)
    testImplementation(libs.aws2.appconfigdata)
    testImplementation(libs.aws2.imds)
    testImplementation(libs.aws2.kinesis)
    testImplementation(libs.aws2.kms)
    testImplementation(libs.aws2.s3)
    testImplementation(libs.aws2.s3control)
    testImplementation(libs.aws2.s3vectors)
    testImplementation(libs.aws2.secretsmanager)
    testImplementation(libs.aws2.sesv2)
    testImplementation(libs.aws2.sns)
    testImplementation(libs.aws2.sns.message.manager)
    testImplementation(libs.aws2.sqs)
    testImplementation(libs.aws2.sts)
    testImplementation(libs.aws2.ssm)

    // Spring Boot (autoconfigure only — no runtime dep)
    compileOnly(libs.spring.boot.autoconfigure)
    compileOnly(libs.spring.boot.testcontainers)
    compileOnly(libs.spring.context.support)
    compileOnly(libs.spring.security.crypto)
    compileOnly(libs.jakarta.mail.api)
    compileOnly(libs.angus.mail)
    annotationProcessor(libs.spring.boot.configuration.processor)

    // Coroutines
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.kotlinx.coroutines.reactive)
    compileOnly(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test)

    // Test
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.micrometer.metrics)
    testImplementation(libs.micrometer.registry.cloudwatch2)
    testImplementation(libs.spring.context.support)
    testImplementation(libs.spring.security.crypto)
    testImplementation(libs.jakarta.mail.api)
    testImplementation(libs.angus.mail)
    testImplementation(bt4k.h2.v2)
    testImplementation(libs.testcontainers.localstack)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(bt4k.mockk)
    testImplementation(libs.awaitility.kotlin)
}

tasks.withType<Test>().configureEach {
    systemProperty("bluetape4k.aws.emulator", System.getProperty("bluetape4k.aws.emulator", "floci"))
    filter.setFailOnNoMatchingTests(true)
    if (providers.gradleProperty("skipAwsEmulatorTests").map(String::toBoolean).orElse(false).get()) {
        exclude("**/*AwsEmulatorTest.class")
    }
}
