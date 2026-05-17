plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

dependencyManagement {
    imports {
        mavenBom(libs.spring.boot.dependencies.get().toString())
        mavenBom(libs.kotlin.bom.get().toString())
        mavenBom(libs.kotlinx.coroutines.bom.get().toString())
    }
}

dependencies {
    implementation(project(":bluetape4k-aws-spring-boot"))

    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.aws2.dynamodb.enhanced)
    implementation(libs.aws2.auth)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactor)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.localstack)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    systemProperty("bluetape4k.aws.emulator", System.getProperty("bluetape4k.aws.emulator", "localstack"))
}
