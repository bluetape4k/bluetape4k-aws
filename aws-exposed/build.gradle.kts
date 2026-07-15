dependencies {
    api(platform(libs.exposed.bom))
    implementation(platform(bt4k.bluetape4k.exposed.bom))
    api(bt4k.exposed.core)
    api(bt4k.exposed.jdbc)
    api(bt4k.bluetape4k.exposed.jdbc)

    implementation(project(":bluetape4k-aws-java"))
    implementation(bt4k.bluetape4k.jdbc)
    implementation(bt4k.hikaricp)

    compileOnly(libs.aws2.rds)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.aws2.rds)
    testImplementation(libs.h2.v2)
    testImplementation(bt4k.postgresql)
    testImplementation(libs.testcontainers.postgresql)
}
