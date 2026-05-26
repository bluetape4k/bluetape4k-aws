plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.noarg)
    alias(libs.plugins.spring.boot) apply false
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

noArg {
    annotation("org.springframework.boot.context.properties.ConfigurationProperties")
}

dependencyManagement {
    imports {
        mavenBom(libs.spring.boot.dependencies.get().toString())
        // Override Spring Boot's kotlin.version=2.2.x back to 2.3.21
        mavenBom(libs.kotlin.bom.get().toString())
        mavenBom(libs.kotlinx.coroutines.bom.get().toString())
    }
}

dependencies {
    // bluetape4k-aws modules
    api(project(":bluetape4k-aws-java"))
    compileOnly(project(":bluetape4k-aws-exposed"))
    compileOnly(project(":bluetape4k-aws-kotlin"))
    compileOnly(libs.aws2.dynamodb.enhanced)
    compileOnly(libs.aws2.kms)
    compileOnly(libs.aws2.s3)
    compileOnly(libs.aws2.s3.transfer.manager)
    compileOnly(libs.aws2.secretsmanager)
    compileOnly(libs.aws2.sesv2)
    compileOnly(libs.aws2.sns)
    compileOnly(libs.aws2.sqs)
    compileOnly(libs.aws2.sts)
    compileOnly(libs.aws2.ssm)

    // bluetape4k artifacts
    api(libs.bluetape4k.io)
    api(libs.bluetape4k.coroutines)
    compileOnly(libs.bluetape4k.jackson3)
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(project(":bluetape4k-aws-exposed"))
    testImplementation(libs.aws2.dynamodb.enhanced)
    testImplementation(libs.aws2.kms)
    testImplementation(libs.aws2.s3)
    testImplementation(libs.aws2.secretsmanager)
    testImplementation(libs.aws2.sesv2)
    testImplementation(libs.aws2.sns)
    testImplementation(libs.aws2.sqs)
    testImplementation(libs.aws2.sts)
    testImplementation(libs.aws2.ssm)

    // Spring Boot (autoconfigure only — no runtime dep)
    compileOnly(libs.spring.boot.autoconfigure)
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
    testImplementation(libs.spring.context.support)
    testImplementation(libs.spring.security.crypto)
    testImplementation(libs.jakarta.mail.api)
    testImplementation(libs.angus.mail)
    testImplementation(libs.h2.v2)
    testImplementation(libs.testcontainers.localstack)
    testImplementation(libs.mockk)
    testImplementation(libs.awaitility.kotlin)
}

tasks.test {
    systemProperty("bluetape4k.aws.emulator", System.getProperty("bluetape4k.aws.emulator", "floci"))
}
