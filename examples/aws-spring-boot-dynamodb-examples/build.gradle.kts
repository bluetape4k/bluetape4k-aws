plugins {
    alias(bt4k.plugins.kotlin.spring)
    alias(bt4k.plugins.spring.boot)
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${bt4k.versions.spring.boot.get()}")
        mavenBom("org.jetbrains.kotlin:kotlin-bom:${bt4k.versions.kotlin.get()}")
        mavenBom("org.jetbrains.kotlinx:kotlinx-coroutines-bom:${bt4k.versions.kotlinx.coroutines.get()}")
    }
}

dependencies {
    implementation(project(":bluetape4k-aws-spring-boot"))

    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.aws2.dynamodb.enhanced)
    implementation(libs.aws2.auth)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactor)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.localstack)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    systemProperty("bluetape4k.aws.emulator", System.getProperty("bluetape4k.aws.emulator", "floci"))
}
