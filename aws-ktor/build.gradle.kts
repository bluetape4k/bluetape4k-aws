configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // bluetape4k-aws modules
    api(project(":bluetape4k-aws-java"))
    api(project(":bluetape4k-aws-kotlin"))
    compileOnly(project(":bluetape4k-aws-exposed"))

    // bluetape4k artifacts
    api(bt4k.bluetape4k.io)
    api(bt4k.bluetape4k.coroutines)
    api(bt4k.bluetape4k.ktor.core)
    compileOnly(bt4k.bluetape4k.jackson3)
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.ktor.testing)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(project(":bluetape4k-aws-exposed"))

    // Ktor client and optional runtime integrations. Keep direct dependencies
    // where aws-ktor exposes Ktor public types or needs a concrete engine.
    api(libs.aws2.auth)
    api(libs.aws2.cloudwatch)
    api(libs.aws2.cloudwatchlogs)
    api(libs.aws2.eventbridge)
    api(libs.aws2.imds)
    api(libs.aws2.kinesis)
    api(libs.aws2.s3control)
    api(libs.aws2.s3vectors)
    api(libs.aws2.sesv2)
    api(libs.aws2.sns)
    api(libs.aws2.sqs)
    api(libs.aws2.sts)
    api(libs.aws.kotlin.dynamodb)
    api(libs.ktor.client.core)
    compileOnly(platform(bt4k.spring.boot4.dependencies))
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
    testImplementation(libs.aws2.kinesis)
    testImplementation(libs.aws2.s3)
    testImplementation(libs.aws2.s3control)
    testImplementation(libs.aws2.s3vectors)
    testImplementation(libs.aws2.sesv2)
    testImplementation(libs.aws2.sns)
    testImplementation(libs.aws2.sqs)
    testImplementation(libs.aws2.sts)
    testImplementation(libs.aws.kotlin.dynamodb)
    testImplementation(platform(bt4k.spring.boot4.dependencies))
    testImplementation(libs.micrometer.core)
    testImplementation(libs.h2.v2)
    testImplementation(libs.testcontainers.localstack)
    testImplementation(libs.mockk)
    testImplementation(libs.awaitility.kotlin)
}

tasks.test {
    systemProperty("bluetape4k.aws.emulator", System.getProperty("bluetape4k.aws.emulator", "floci"))
}
