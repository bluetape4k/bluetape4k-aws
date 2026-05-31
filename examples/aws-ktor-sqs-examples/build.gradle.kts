plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":bluetape4k-aws-ktor"))

    implementation(libs.bluetape4k.ktor.core)
    // Direct Ktor artifacts remain intentional: the example runs on CIO and uses Jackson event DTO binding.
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.jackson)
    implementation(libs.aws2.sqs)
    implementation(libs.aws2.auth)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.ktor.testing)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.localstack)
    testImplementation(libs.kotlinx.coroutines.test)
    // Jackson remains intentional because the example exposes Jackson-serialized event DTOs.
    testImplementation(libs.ktor.client.content.negotiation)
}

tasks.test {
    systemProperty("bluetape4k.aws.emulator", System.getProperty("bluetape4k.aws.emulator", "floci"))
}
