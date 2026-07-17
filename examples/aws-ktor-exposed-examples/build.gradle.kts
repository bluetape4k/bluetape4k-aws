plugins {
    alias(bt4k.plugins.exposed.plugin)
}

exposed {
    migrations {
        tablesPackage = "io.bluetape4k.aws.examples.ktor.exposed"
        databaseUrl = "jdbc:h2:mem:aws-ktor-exposed-examples-migrations;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
        databaseUser = "sa"
        databasePassword = ""
    }
}

dependencies {
    implementation(project(":bluetape4k-aws-exposed"))
    implementation(project(":bluetape4k-aws-ktor"))

    implementation(platform(bt4k.exposed.bom))
    implementation(platform(bt4k.bluetape4k.exposed.bom))
    implementation(bt4k.bluetape4k.exposed.jdbc)
    implementation(bt4k.bluetape4k.ktor.core)
    implementation(bt4k.exposed.jdbc)
    // Direct Ktor artifacts remain intentional: the example runs on CIO and uses Jackson DTO binding.
    implementation(libs.ktor.serialization.jackson)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.kotlinx.coroutines.core)

    runtimeOnly(bt4k.postgresql)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.ktor.testing)
    testImplementation(bt4k.bluetape4k.testcontainers)
    // Jackson remains intentional because the example DTOs are Jackson-serialized Exposed records.
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.testcontainers.postgresql)
}
