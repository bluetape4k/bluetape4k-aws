configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // bluetape4k-aws modules
    api(project(":bluetape4k-aws-java"))
    api(project(":bluetape4k-aws-kotlin"))
    compileOnly(project(":bluetape4k-aws-exposed"))

    // bluetape4k artifacts
    api(libs.bluetape4k.io)
    api(libs.bluetape4k.coroutines)
    api(libs.bluetape4k.ktor.core)
    compileOnly(libs.bluetape4k.jackson3)
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.ktor.testing)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(project(":bluetape4k-aws-exposed"))

    // Ktor client and optional runtime integrations. Keep direct dependencies
    // where aws-ktor exposes Ktor public types or needs a concrete engine.
    api(libs.aws2.auth)
    compileOnly(libs.aws2.cloudwatch)
    compileOnly(libs.aws2.cloudwatchlogs)
    compileOnly(libs.aws2.eventbridge)
    compileOnly(libs.aws2.imds)
    compileOnly(libs.aws2.s3control)
    compileOnly(libs.aws2.s3vectors)
    compileOnly(libs.aws2.sesv2)
    compileOnly(libs.aws2.sns)
    compileOnly(libs.aws2.sqs)
    compileOnly(libs.aws.kotlin.dynamodb)
    api(libs.ktor.client.core)
    compileOnly(platform(libs.spring.boot.dependencies))
    compileOnly(libs.micrometer.core)
    compileOnly(libs.ktor.client.cio)
    compileOnly(libs.ktor.client.content.negotiation)
    compileOnly(libs.ktor.serialization.jackson)

    // Coroutines
    compileOnly(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)

    // Test
    testImplementation(libs.aws2.cloudwatch)
    testImplementation(libs.aws2.cloudwatchlogs)
    testImplementation(libs.aws2.eventbridge)
    testImplementation(libs.aws2.imds)
    testImplementation(libs.aws2.s3)
    testImplementation(libs.aws2.s3control)
    testImplementation(libs.aws2.s3vectors)
    testImplementation(libs.aws2.sesv2)
    testImplementation(libs.aws2.sns)
    testImplementation(libs.aws2.sqs)
    testImplementation(libs.aws.kotlin.dynamodb)
    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation(libs.micrometer.core)
    testImplementation(libs.h2.v2)
    testImplementation(libs.testcontainers.localstack)
    testImplementation(libs.mockk)
    testImplementation(libs.awaitility.kotlin)
}

tasks.test {
    systemProperty("bluetape4k.aws.emulator", System.getProperty("bluetape4k.aws.emulator", "floci"))
}
