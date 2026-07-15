dependencies {
    implementation(project(":bluetape4k-aws-ktor"))

    implementation(bt4k.bluetape4k.ktor.core)
    // Direct Ktor artifacts remain intentional: the example owns its client/server engines.
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.server.cio)
    implementation(libs.aws2.auth)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.ktor.testing)
    testImplementation(libs.kotlinx.coroutines.test)
}
