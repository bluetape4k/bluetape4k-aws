configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // bluetape4k artifacts (via BOM)
    api(bt4k.bluetape4k.io)
    api(bt4k.bluetape4k.coroutines)
    compileOnly(bt4k.bluetape4k.jackson3)
    compileOnly(bt4k.bluetape4k.resilience4j)
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(bt4k.bluetape4k.idgenerators)

    // AWS Kotlin SDK Core (via BOM is not available; explicit versions via catalog)
    api(bt4k.aws.kotlin.core)
    api(libs.aws.kotlin.aws.config)
    api(libs.aws.kotlin.aws.endpoint)
    api(libs.aws.smithy.kotlin.http)
    api(libs.aws.smithy.kotlin.http.client.engine.crt)
    implementation(libs.aws.smithy.kotlin.http.client.engine.default)
    implementation(libs.aws.smithy.kotlin.http.client.engine.okhttp)

    // AWS Kotlin SDK Services (compileOnly — consumer adds runtime deps)
    compileOnly(libs.aws.kotlin.bedrock.runtime)
    compileOnly(libs.aws.kotlin.dynamodb)
    compileOnly(libs.aws.kotlin.s3)
    compileOnly(libs.aws.kotlin.ses)
    compileOnly(libs.aws.kotlin.sesv2)
    compileOnly(libs.aws.kotlin.secretsmanager)
    compileOnly(libs.aws.kotlin.sns)
    compileOnly(libs.aws.kotlin.sqs)
    compileOnly(libs.aws.kotlin.ssm)
    compileOnly(libs.aws.kotlin.kms)
    compileOnly(libs.aws.kotlin.cloudwatch)
    compileOnly(libs.aws.kotlin.cloudwatchlogs)
    compileOnly(libs.aws.kotlin.kinesis)
    compileOnly(libs.aws.kotlin.eventbridge)
    compileOnly(libs.aws.kotlin.scheduler)
    compileOnly(libs.aws.kotlin.sts)

    // Resilience4j
    compileOnly(libs.resilience4j.retry)
    compileOnly(libs.resilience4j.kotlin)

    // Jackson
    compileOnly(libs.jackson.module.kotlin)
    compileOnly(libs.jackson.module.blackbird)

    // Coroutines
    compileOnly(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)

    // Test
    testImplementation(libs.aws.kotlin.bedrock.runtime)
    testImplementation(libs.aws.kotlin.secretsmanager)
    testImplementation(libs.aws.kotlin.ssm)
    testImplementation(libs.aws.kotlin.eventbridge)
    testImplementation(libs.aws.kotlin.scheduler)
    testImplementation(libs.testcontainers.localstack)
    testImplementation(libs.mockk)
    testImplementation(libs.awaitility.kotlin)
}

tasks.test {
    val smokeRequested = providers.gradleProperty("bedrockSmoke").isPresent
    val missingSmokeInputs = listOf("BEDROCK_REGION", "BEDROCK_MODEL_ID")
        .filter { providers.environmentVariable(it).orNull.isNullOrBlank() }
    val smokeEnabled = smokeRequested && missingSmokeInputs.isEmpty()

    systemProperty("bluetape4k.aws.emulator", System.getProperty("bluetape4k.aws.emulator", "floci"))
    useJUnitPlatform {
        if (smokeEnabled) {
            includeTags("bedrock-smoke")
        } else {
            excludeTags("bedrock-smoke")
        }
    }
    onlyIf(
        "bedrock-smoke: SKIP before client creation; missing=${missingSmokeInputs.joinToString(",")}",
    ) {
        !smokeRequested || smokeEnabled
    }
}
