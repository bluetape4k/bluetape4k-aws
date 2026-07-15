plugins {
    alias(bt4k.plugins.kotlin.spring)
    alias(bt4k.plugins.spring.boot)
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
        mavenBom("org.springframework.boot:spring-boot-dependencies:${bt4k.versions.spring.boot.get()}")
        mavenBom("org.jetbrains.kotlin:kotlin-bom:${bt4k.versions.kotlin.get()}")
        mavenBom("org.jetbrains.kotlinx:kotlinx-coroutines-bom:${bt4k.versions.kotlinx.coroutines.get()}")
    }
}

dependencies {
    implementation(project(":bluetape4k-aws-exposed"))
    implementation(project(":bluetape4k-aws-spring-boot"))

    implementation(platform(libs.exposed.bom))
    implementation(platform(bt4k.bluetape4k.exposed.bom))
    implementation(bt4k.bluetape4k.exposed.jdbc)
    implementation(bt4k.exposed.jdbc)
    implementation(libs.spring.boot.starter.web)

    runtimeOnly(bt4k.postgresql)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.postgresql)
}
