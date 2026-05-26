plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(bt4k.plugins.exposed.plugin)
}

exposed {
    migrations {
        tablesPackage = "io.bluetape4k.aws.examples.spring.exposed"
        databaseUrl = "jdbc:h2:mem:aws-spring-boot-exposed-examples-migrations;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
        databaseUser = "sa"
        databasePassword = ""
    }
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
