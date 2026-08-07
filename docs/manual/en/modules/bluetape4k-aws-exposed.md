---
manualId: "bluetape4k-aws-exposed"
id: "bluetape4k-aws-exposed"
title: "AWS Exposed Database Integration"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-aws-exposed"
sourceDir: "aws-exposed"
releaseRef: "0.5.0"
artifact: io.github.bluetape4k.aws:bluetape4k-aws-exposed
---

# AWS Exposed Database Integration

> Library manual grounded in the 0.5.0 release source.

## Problem {#problem}

Resolves AWS-backed database settings, creates Hikari data sources and Exposed databases, and manages default or named database handles.

## When to use it {#when-to-use}

Use it when JDBC connection settings or credentials come from Secrets Manager, Parameter Store, or RDS IAM authentication.

## Coordinates {#coordinates}

Applications select one central BOM version.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-exposed")
}
```

AWS service SDKs follow a `compileOnly` policy; add the services actually used as runtime dependencies.

## Core concepts {#concepts}

A config source identifies where settings live; a resolver returns concrete properties; the data-source factory creates Hikari; the database factory connects Exposed and returns a closeable handle.

## Quick start {#quick-start}

```kotlin
val factory = AwsExposedDatabaseFactory(resolver, dataSourceFactory)
val handle = factory.create("orders", properties)
try {
    transaction(handle.database) { Orders.selectAll().count() }
} finally {
    handle.close()
}
```

## API by task {#api-by-task}

`AwsDatabaseProperties`, `AwsDatabaseSettingsResolver`, `AwsJdbcDataSourceFactory`, `AwsExposedDatabaseFactory`, `AwsExposedDatabaseRegistry`, and RDS IAM authentication support.

## Recommended patterns {#patterns}

Put client and background-job ownership at one application boundary. Configure region, credentials, and endpoints once instead of rebuilding them per call.

## Integrations {#integrations}

Spring Boot auto-configuration and Ktor's Exposed plugin consume this foundation. Add the required Secrets Manager, SSM, RDS, JDBC driver, Hikari, and Exposed runtime modules.

## Configuration {#configuration}

Keep secrets out of static configuration. Configure source, region, secret/parameter identifier, JDBC driver and URL shape, pool size, and IAM token refresh behavior.

## Failure modes {#failures}

Malformed secret payloads, missing driver jars, expired IAM tokens, wrong region, and pool ownership mistakes surface during resolution or first connection.

## Operations {#operations}

Resolve settings before serving traffic, bound pool size, rotate credentials outside transactions, and close every handle during application shutdown.

## Testing {#testing}

Use fake resolvers for unit tests and an emulator or disposable database for resolution-plus-connection tests. Assert that close releases Hikari resources.

## Workshops and learning path {#workshops}

Follow `database-settings`, `rds-iam-and-hikari`, and `transaction-boundaries`, then run the Spring Boot and Ktor Exposed examples.

## Limitations {#limitations}

This module resolves and owns infrastructure; it does not design Exposed tables or move blocking JDBC work off coroutine threads automatically.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `0.5.0` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### AWS Exposed architecture diagram

[![AWS Exposed architecture diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-exposed-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-exposed-architecture-01.svg)

_Release README: [`aws-exposed/README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-exposed/README.md)_

### AWS Exposed configuration flow diagram

[![AWS Exposed configuration flow diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-exposed-flow-02.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-exposed-flow-02.svg)

_Release README: [`aws-exposed/README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-exposed/README.md)_

### AWS Exposed database handle sequence diagram

[![AWS Exposed database handle sequence diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-exposed-sequence-03.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-exposed-sequence-03.svg)

_Release README: [`aws-exposed/README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-exposed/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [Release source: `aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsExposedDatabaseFactory.kt`](../../../../aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsExposedDatabaseFactory.kt)
- [Release source: `aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsRdsIamAuthentication.kt`](../../../../aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsRdsIamAuthentication.kt)
- [Release test: `AwsExposedDatabaseFactoryTest`](../../../../aws-exposed/src/test/kotlin/io/bluetape4k/aws/exposed/AwsExposedDatabaseFactoryTest.kt)
