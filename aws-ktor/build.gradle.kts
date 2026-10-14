configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // bluetape4k-aws modules
    api(project(":bluetape4k-aws-java"))
    api(project(":bluetape4k-aws-kotlin"))
    compileOnly(project(":bluetape4k-aws-exposed"))

    // Selective Exposed Ktor health integration remains compileOnly here. Consumers
    // opt in to the backend adapter they actually run (the example adds core+jdbc).
    compileOnly(platform(bt4k.bluetape4k.exposed.bom))
    compileOnly("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-core")
    compileOnly("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-jdbc")

    // bluetape4k artifacts
    api(bt4k.bluetape4k.io)
    api(bt4k.bluetape4k.ktor.core)
    compileOnly(bt4k.bluetape4k.jackson3)
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(testFixtures(project(":bluetape4k-aws-java")))
    testImplementation(bt4k.bluetape4k.ktor.testing)
    testImplementation(bt4k.bluetape4k.testcontainers)

    // Ktor client and optional runtime integrations. Keep direct dependencies
    // where aws-ktor exposes Ktor public types or needs a concrete engine.
    compileOnly(libs.aws2.auth)
    compileOnly(libs.aws2.cloudwatch)
    compileOnly(libs.aws2.cloudwatchlogs)
    compileOnly(libs.aws2.eventbridge)
    compileOnly(libs.aws2.imds)
    compileOnly(libs.aws2.kinesis)
    compileOnly(libs.aws2.s3)
    compileOnly(libs.aws2.s3control)
    compileOnly(libs.aws2.s3vectors)
    compileOnly(libs.aws2.sesv2)
    compileOnly(libs.aws2.sns)
    compileOnly(libs.aws2.sqs)
    compileOnly(libs.aws2.sts)
    compileOnly(libs.aws.kotlin.dynamodb)
    compileOnly(libs.ktor.client.core)
    
    compileOnly(platform(bt4k.spring.boot4.dependencies))
    compileOnly(libs.micrometer.core)
    compileOnly(libs.ktor.client.cio)
    compileOnly(libs.ktor.client.content.negotiation)
    compileOnly(libs.ktor.serialization.jackson)

    // Coroutines
    api(bt4k.bluetape4k.coroutines)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)

    // Test
    testImplementation(bt4k.h2.v2)
    testImplementation(libs.testcontainers.localstack)
    testImplementation(bt4k.mockk)
    testImplementation(libs.awaitility.kotlin)
}

tasks.test {
    systemProperty("bluetape4k.aws.emulator", System.getProperty("bluetape4k.aws.emulator", "floci"))
}
