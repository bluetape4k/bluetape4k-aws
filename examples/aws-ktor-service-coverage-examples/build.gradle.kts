plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":bluetape4k-aws-ktor"))

    implementation(libs.bluetape4k.jackson3)
    implementation(libs.bluetape4k.ktor.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.jackson)
    implementation(libs.aws2.sesv2)
    implementation(libs.aws2.sns)
    implementation(libs.aws2.cloudwatch)
    implementation(libs.aws2.cloudwatchlogs)
    implementation(libs.aws2.kinesis)
    implementation(libs.aws2.sts)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.ktor.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.content.negotiation)
}
