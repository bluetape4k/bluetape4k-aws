dependencies {
    api(platform(libs.exposed.bom))
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    api(libs.bluetape4k.exposed.jdbc)

    implementation(libs.hikaricp)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.h2.v2)
    testImplementation(libs.postgresql.driver)
    testImplementation(libs.testcontainers.postgresql)
}
