configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    val awsKotlinVersion = bt4k.versions.aws.kotlin.get()

    // bluetape4k-aws modules
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
    testImplementation(testFixtures(project(":bluetape4k-aws-kotlin")))
    testImplementation(bt4k.bluetape4k.ktor.testing)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.aws.kotlin.s3)

    // Ktor client and optional runtime integrations. Keep direct dependencies
    // where aws-ktor exposes Ktor public types or needs a concrete engine.
    api("aws.sdk.kotlin:cloudwatch:$awsKotlinVersion")
    api("aws.sdk.kotlin:cloudwatchlogs:$awsKotlinVersion")
    api("aws.sdk.kotlin:eventbridge:$awsKotlinVersion")
    api("aws.sdk.kotlin:kinesis:$awsKotlinVersion")
    api("aws.sdk.kotlin:dynamodb:$awsKotlinVersion")
    api("aws.sdk.kotlin:s3control:$awsKotlinVersion")
    api("aws.sdk.kotlin:s3vectors:$awsKotlinVersion")
    api("aws.sdk.kotlin:sesv2:$awsKotlinVersion")
    api("aws.sdk.kotlin:sns:$awsKotlinVersion")
    api("aws.sdk.kotlin:sqs:$awsKotlinVersion")
    api("aws.sdk.kotlin:sts:$awsKotlinVersion")
    api(libs.aws.smithy.aws.credentials)
    api(libs.aws.smithy.aws.signing.common)
    api(libs.aws.smithy.http.client)
    api(libs.ktor.client.core)

    implementation(libs.aws.smithy.aws.signing.default)
    implementation(bt4k.aws.smithy.kotlin.http.client.engine.okhttp)
    compileOnly(bt4k.okhttp3)

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
