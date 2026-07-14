---
title: Resolving database settings
description: Resolve AWS-backed JDBC settings before building pools and serving traffic.
manualId: bluetape4k-aws-exposed
chapterId: database-settings
---

# Resolving database settings

AWS-backed database configuration is a staged pipeline, not a string lookup hidden inside a transaction.

## Resolution pipeline

`AwsDatabaseConnectionProperties` carries local defaults plus optional Secrets Manager and Parameter Store descriptors. An `AwsDatabaseSettingsResolver` resolves those descriptors into concrete URL, driver, username, password or IAM settings before the pool is created.

```kotlin
val properties = AwsDatabaseConnectionProperties(
    secretSource = AwsDatabaseConfigSource(
        type = AwsDatabaseConfigSourceType.SECRETS_MANAGER,
        sourceId = "prod/orders-db",
    ),
    pool = AwsDatabasePoolProperties(maximumPoolSize = 12),
)
val resolved = resolver.resolve("orders", properties)
```

## Precedence must be explicit

Choose and document whether remote values replace empty local fields or override configured fields. The resolver boundary exists so Spring, Ktor, tests, and custom deployments can apply one deterministic rule rather than silently mixing sources.

## Secret handling

Passwords use `AwsSecretString` so diagnostics are redacted. Reveal the value only when the JDBC driver needs it. Never serialize resolved properties to logs or health endpoints.

## Validation before traffic

Validate nonblank database names, driver class, URL, authentication mode, pool limits, and source identifiers during startup. An optional source may be absent; a required source must fail startup rather than produce a half-configured pool.

## Testing resolvers

Use a fake resolver to prove precedence and redaction without AWS. Add emulator tests for the remote payload shape and a disposable JDBC database test for the final resolved connection.

## Sources

- [Database properties](../../../../../aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsDatabaseProperties.kt)
- [Config source model](../../../../../aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsDatabaseConfigSource.kt)
- [Settings resolver contract](../../../../../aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsDatabaseSettingsResolver.kt)

