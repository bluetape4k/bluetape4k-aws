dependencies {
    implementation(project(":aws-ktor"))

    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.aws2.auth)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.server.test.host)
}
