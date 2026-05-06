plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.noarg)
    alias(libs.plugins.spring.boot) apply false
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

noArg {
    annotation("org.springframework.boot.context.properties.ConfigurationProperties")
}

dependencyManagement {
    imports {
        mavenBom(libs.spring.boot.dependencies.get().toString())
        // Override Spring Boot's kotlin.version=2.2.x back to 2.3.21
        mavenBom(libs.kotlin.bom.get().toString())
        mavenBom(libs.kotlinx.coroutines.bom.get().toString())
    }
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

    // Spring Boot (autoconfigure only — no runtime dep)
    compileOnly(libs.spring.boot.autoconfigure)
    annotationProcessor(libs.spring.boot.configuration.processor)

    // Coroutines
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.kotlinx.coroutines.reactive)
    compileOnly(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.kotlinx.coroutines.test)

    // Test
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.localstack)
    testImplementation(libs.mockk)
    testImplementation(libs.awaitility.kotlin)
}

tasks.test {
    systemProperty("bluetape4k.aws.emulator", System.getProperty("bluetape4k.aws.emulator", "localstack"))
}
