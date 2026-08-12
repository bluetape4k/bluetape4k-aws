---
manualId: "repository-overview"
title: "Bluetape4k AWS Manual"
locale: "en"
releaseRef: "0.5.0"
---

# Bluetape4k AWS Manual

`bluetape4k-aws` connects Kotlin/JVM applications to AWS through two SDK paths and two application frameworks. It provides coroutine adapters for AWS SDK for Java v2, small extensions around the AWS SDK for Kotlin, Spring Boot auto-configuration, Ktor plugins, and an Exposed JDBC bridge for AWS-backed database settings.

This manual is organized around decisions rather than package names. Start by choosing the Java or Kotlin SDK path, then choose direct library use, Spring Boot, or Ktor. Service guides for S3, DynamoDB, and SQS/SNS lead to runnable examples, while the database and operations guides make ownership and shutdown boundaries explicit.

## Core capabilities

- **Two SDK paths:** [SDK selection](guides/sdk-selection.md) explains when to extend AWS SDK for Java v2 with coroutine adapters and when to use the AWS SDK for Kotlin directly.
- **Coroutine-friendly service APIs:** The [Java SDK extensions](modules/bluetape4k-aws-java.md) bridge sync and async clients into structured Kotlin execution, while the [Kotlin SDK extensions](modules/bluetape4k-aws-kotlin.md) reduce request and response boilerplate.
- **Service patterns and examples:** [Service learning paths](guides/service-learning-paths.md) connect S3, DynamoDB, SQS, and SNS helpers to runnable Ktor and Spring Boot examples.
- **Application frameworks:** [Spring Boot and Ktor](guides/spring-vs-ktor.md) integrations manage client construction, configuration, lifecycle, and request signing at the appropriate framework boundary.
- **Database integration:** The [Exposed database guide](guides/database-with-exposed.md) turns AWS-provided database settings and RDS IAM authentication into explicit JDBC configuration.
- **Testing and operations:** [Testing and operations](guides/testing-and-operations.md) separates emulator proof from AWS integration proof and defines client ownership, shutdown, retry, and observability checks.

## Version baseline

Applications select one central BOM version: `io.github.bluetape4k:bluetape4k-dependencies:<version>`. They do not need to coordinate the `bluetape4k-aws`, AWS SDK, and related bluetape4k library versions independently.

The technical baseline of this manual is `bluetape4k-aws 0.5.0`. It covers the 6 published projects and 8 runnable example projects present in that stable tag. Develop-only projects are intentionally excluded.

- Release tag: [`0.5.0`](https://github.com/bluetape4k/bluetape4k-aws/tree/0.5.0)
- Release commit: [`664e4dfb544a3c19db484b0f9a8e023a73774b49`](https://github.com/bluetape4k/bluetape4k-aws/commit/664e4dfb544a3c19db484b0f9a8e023a73774b49)
- Runtime baseline: JDK 25, Kotlin 2.3, Spring Boot 4, and Ktor 3

## Where to start

- Use [Getting started](getting-started.md) to import the central BOM and add only the AWS service modules your application uses.
- Read [SDK selection](guides/sdk-selection.md) before mixing AWS SDK for Java v2 and AWS SDK for Kotlin in the same service.
- Open the [repository map](architecture/repository-map.md) to see how the 14 release projects fit together.
- Follow the [learning path](guides/learning-path.md) for a goal-oriented route through S3, DynamoDB, messaging, and relational database examples.
- Read [testing and operations](guides/testing-and-operations.md) before deciding emulator coverage, client ownership, and shutdown policy.

## Responsibility boundary

This repository owns AWS client adaptation and framework integration. It does not hide AWS service semantics, IAM policy, retry safety, or resource ownership. Applications still choose the service SDK modules, credentials and region providers, timeout and retry policy, idempotency strategy, and production observability.

## Sources

- [Release module registry](../../../settings.gradle.kts)
- [Repository dependency and module overview](../../../README.md)
