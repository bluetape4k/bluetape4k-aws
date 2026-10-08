configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(platform(bt4k.exposed.bom))
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
    testImplementation(bt4k.h2.v2)
    testImplementation(bt4k.postgresql)
    testImplementation(libs.testcontainers.postgresql)

    // Binary Serializers
    testImplementation(bt4k.bluetape4k.io)
    testImplementation(bt4k.fory.kotlin)
    testImplementation(bt4k.at.yawk.lz4.java)
}
