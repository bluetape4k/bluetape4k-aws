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
    implementation(project(":bluetape4k-aws-exposed"))
    implementation(project(":bluetape4k-aws-spring-boot"))

    implementation(platform(libs.exposed.bom))
    implementation(platform(libs.bluetape4k.exposed.bom))
    implementation(libs.bluetape4k.exposed.jdbc)
    implementation(libs.exposed.jdbc)
    implementation(libs.spring.boot.starter.web)

    runtimeOnly(libs.postgresql.driver)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.postgresql)
}
