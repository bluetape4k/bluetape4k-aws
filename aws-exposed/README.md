# bluetape4k-aws-exposed

[English](README.md) | [한국어](README.ko.md)

Shared Exposed JDBC foundation for AWS-backed database configuration.

## Features

- `AwsDatabaseProperties` for default and named databases.
- `AwsDatabaseSettingsResolver` for framework-specific Secrets Manager or
  Parameter Store resolution.
- `AwsSecretString` for redacted password diagnostics.
- `AwsExposedDatabaseFactory` for Hikari-backed Exposed `Database` creation.
- `AwsExposedDatabaseRegistry` for default and named handles.

This module does not fetch AWS values by itself. Spring Boot and Ktor adapters
resolve AWS configuration and pass the final JDBC settings to this foundation.

## Dependency

```kotlin
dependencies {
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-exposed:0.1.0-SNAPSHOT")
}
```

## Usage

```kotlin
val factory = AwsExposedDatabaseFactory()
val handle = factory.create(
    properties = AwsDatabaseConnectionProperties(
        url = "jdbc:postgresql://localhost:5432/app",
        driverClassName = "org.postgresql.Driver",
        username = "app",
        password = AwsSecretString.of("secret"),
    )
)

transaction(handle.database) {
    // Run bluetape4k-exposed repositories or Exposed DSL here.
}
```

## Local Verification

```bash
./gradlew :bluetape4k-aws-exposed:test
```

Tests use H2 and PostgreSQL Testcontainers. They do not require real AWS
credentials.
