---
manualId: "database-with-exposed"
title: "AWS-backed Configuration to Exposed JDBC"
locale: "en"
releaseRef: "0.5.0"
---

# AWS-backed Configuration to Exposed JDBC

`bluetape4k-aws-exposed` connects AWS-sourced database settings to a Hikari data source and an Exposed JDBC `Database`. The path has four separate responsibilities: locate configuration, resolve credentials, create and own the pool, and execute transactions. Keeping them separate prevents secret retrieval or IAM token refresh from leaking into repository code.

![AWS configuration to Exposed database integration flow](../../assets/database/integration-flow.png)

## The data path

1. `AwsDatabaseProperties` describes the default database and optional named databases.
2. `AwsDatabaseConfigSource` identifies Secrets Manager or Parameter Store without fetching it directly.
3. An `AwsDatabaseSettingsResolver` implemented by the framework layer resolves remote values and optional RDS IAM credentials.
4. `AwsExposedDatabaseFactory` validates the resolved JDBC settings, creates a Hikari data source, and calls `Database.connect(dataSource)`.
5. `AwsExposedDatabaseRegistry` exposes the default and named handles and closes their data sources in reverse order.

The factory does not start transactions. Repository or service code remains responsible for an explicit Exposed transaction boundary.

## Choose the credential source

| Source | Use when | Operational concern |
| --- | --- | --- |
| Static/application properties | Local development or externally injected secrets | Keep secrets out of committed configuration |
| Secrets Manager | A structured secret stores JDBC URL, username, password, or related fields | Define refresh and cache behavior; do not fetch on every query |
| Parameter Store | Parameters are organized by path and environment | Define prefix mapping, optional values, and refresh behavior |
| RDS IAM authentication | The driver connects to an IAM-enabled RDS endpoint | Tokens expire; generate near connection creation and preserve TLS requirements |

`AwsDatabaseConfigSource` is intentionally storage-neutral. The foundation module does not own AWS clients. Spring Boot and Ktor adapters resolve values with their own clients and lifecycle.

## Default and named databases

Create one default handle for the common path and named handles only when the application truly has multiple pools. A registry lookup fails for an unknown name instead of silently falling back to the default. If registry creation fails halfway through, the factory closes already-created handles before rethrowing the original error.

Close the registry once at application shutdown. Spring Boot wires it as a closeable bean; the Ktor plugin closes its runtime-owned registry. If the application builds the factory directly, the application owns the registry.

## Learn from the examples

- [Spring Boot Exposed example](../../../../examples/aws-spring-boot-exposed-examples/README.md) separates HTTP behavior, service transactions, schema setup, and auto-configured database resources.
- [Ktor Exposed example](../../../../examples/aws-ktor-exposed-examples/README.md) keeps routes, Exposed queries, and plugin lifecycle in separate layers.

Both examples use PostgreSQL Testcontainers. That proves the Hikari/driver/Exposed path against PostgreSQL, but it does not prove Secrets Manager, Parameter Store, RDS IAM, production TLS, or production pool sizing unless those paths are tested separately.

## Relation to bluetape4k-exposed

This module builds on the JDBC path in [`bluetape4k-exposed`](https://github.com/bluetape4k/bluetape4k-exposed). Use the Exposed manual for repository patterns, transaction boundaries, database adapters, and JDBC-versus-R2DBC decisions. The AWS module is a configuration and lifecycle bridge; it does not replace the Exposed data-access model.

## Sources

- [Configuration source descriptor](../../../../aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsDatabaseConfigSource.kt)
- [Database settings resolver](../../../../aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsDatabaseSettingsResolver.kt)
- [Exposed database factory](../../../../aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsExposedDatabaseFactory.kt)
- [Database registry](../../../../aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsExposedDatabaseRegistry.kt)
- [RDS IAM support](../../../../aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsRdsIamAuthentication.kt)
