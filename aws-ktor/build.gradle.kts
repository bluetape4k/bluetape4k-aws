configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // bluetape4k-aws modules
    api(project(":aws"))
    compileOnly(project(":aws-kotlin"))

    // bluetape4k artifacts
    api(libs.bluetape4k.io)
    api(libs.bluetape4k.coroutines)
    compileOnly(libs.bluetape4k.jackson2)
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)

    // Ktor server
    compileOnly(libs.ktor.server.core)
    compileOnly(libs.ktor.client.core)
    compileOnly(libs.ktor.client.cio)
    compileOnly(libs.ktor.client.content.negotiation)
    compileOnly(libs.ktor.serialization.jackson)

    // Coroutines
    compileOnly(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)

    // Test
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.testcontainers.localstack)
    testImplementation(libs.mockk)
    testImplementation(libs.awaitility.kotlin)
}

tasks.test {
    systemProperty("bluetape4k.aws.emulator", System.getProperty("bluetape4k.aws.emulator", "localstack"))
}
