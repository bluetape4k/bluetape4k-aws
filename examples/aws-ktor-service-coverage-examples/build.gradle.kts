plugins {
    alias(bt4k.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":bluetape4k-aws-ktor"))

    implementation(bt4k.bluetape4k.jackson3)
    implementation(bt4k.bluetape4k.ktor.core)
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

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.ktor.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.content.negotiation)
}
