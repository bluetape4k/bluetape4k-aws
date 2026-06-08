dependencies {
    api(platform(libs.exposed.bom))
    implementation(platform(libs.bluetape4k.exposed.bom))
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    api(libs.bluetape4k.exposed.jdbc)

    implementation(libs.bluetape4k.jdbc)
    implementation(libs.hikaricp)

    compileOnly(libs.aws2.rds)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.aws2.rds)
    testImplementation(libs.h2.v2)
    testImplementation(libs.postgresql.driver)
    testImplementation(libs.testcontainers.postgresql)
}
