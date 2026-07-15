plugins {
    alias(bt4k.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":bluetape4k-aws-ktor"))

    implementation(bt4k.bluetape4k.ktor.core)
    // Direct Ktor artifacts remain intentional: the example runs on CIO and uses Jackson DTO binding.
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.jackson)
    implementation(libs.aws.kotlin.dynamodb)
    implementation(libs.aws2.auth)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.ktor.testing)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.localstack)
    testImplementation(libs.kotlinx.coroutines.test)
    // Jackson remains intentional because the example DTOs are Jackson-serialized AWS models.
    testImplementation(libs.ktor.client.content.negotiation)
}

tasks.test {
    systemProperty("bluetape4k.aws.emulator", System.getProperty("bluetape4k.aws.emulator", "floci"))
}
