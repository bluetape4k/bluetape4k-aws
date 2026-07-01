plugins {
    alias(libs.plugins.kotlin.spring)
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // bluetape4k artifacts (via BOM)
    api(libs.bluetape4k.core)
    api(libs.bluetape4k.io)
    api(libs.bluetape4k.netty)
    api(libs.bluetape4k.idgenerators)
    compileOnly(libs.bluetape4k.jackson3)
    compileOnly(libs.bluetape4k.resilience4j)
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)

    // AWS SDK v2 Core (via BOM)
    api(libs.aws2.aws.core)
    api(libs.aws2.apache.client)
    api(libs.aws2.aws.crt.client)
    api(libs.aws2.netty.nio.client)
    compileOnly(libs.aws2.url.connection.client)

    // AWS SDK v2 Services (compileOnly — consumer adds runtime deps)
    compileOnly(libs.aws2.dynamodb.enhanced)
    compileOnly(libs.aws2.s3)
    compileOnly(libs.aws2.s3vectors)
    compileOnly(libs.aws2.s3.transfer.manager)
    compileOnly(libs.aws2.aws.crt)
    compileOnly(libs.aws2.ses)
    compileOnly(libs.aws2.sesv2)
    compileOnly(libs.aws2.secretsmanager)
    compileOnly(libs.aws2.sns)
    compileOnly(libs.aws2.sqs)
    compileOnly(libs.aws2.ssm)
    compileOnly(libs.aws2.kms)
    compileOnly(libs.aws2.cloudwatch)
    compileOnly(libs.aws2.cloudwatchlogs)
    compileOnly(libs.aws2.kinesis)
    compileOnly(libs.aws2.eventbridge)
    compileOnly(libs.aws2.rds)
    compileOnly(libs.aws2.sts)

    // Coroutines
    compileOnly(libs.bluetape4k.coroutines)
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.kotlinx.coroutines.reactive)
    compileOnly(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test)

    // Test
    testImplementation(libs.aws2.ec2)
    testImplementation(libs.aws2.rds)
    testImplementation(libs.aws2.secretsmanager)
    testImplementation(libs.aws2.s3vectors)
    testImplementation(libs.aws2.ssm)
    testImplementation(libs.aws2.eventbridge)
    testImplementation(libs.aws2.test.utils)
    testImplementation(libs.testcontainers.localstack)
    testImplementation(libs.mockk)
    testImplementation(libs.awaitility.kotlin)

    // Spring Boot (DynamoDB 예제 테스트용)
    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation(platform(libs.kotlin.bom))  // Spring Boot's kotlin.version override
    testImplementation("org.springframework.boot:spring-boot-starter-webflux")
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(group = "junit", module = "junit")
        exclude(module = "mockito-core")
    }
}

tasks.test {
    systemProperty("bluetape4k.aws.emulator", System.getProperty("bluetape4k.aws.emulator", "floci"))
}
