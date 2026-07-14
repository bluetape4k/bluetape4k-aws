---
manualId: "repository-overview"
title: "Bluetape4k AWS Manual"
locale: "en"
releaseRef: "0.4.0"
---

# Bluetape4k AWS Manual

`bluetape4k-aws` connects Kotlin/JVM applications to AWS through two SDK paths and two application frameworks. It provides coroutine adapters for AWS SDK for Java v2, small extensions around the AWS SDK for Kotlin, Spring Boot auto-configuration, Ktor plugins, and an Exposed JDBC bridge for AWS-backed database settings.

This manual is organized around decisions rather than package names. Start by choosing the Java or Kotlin SDK path, then choose direct library use, Spring Boot, or Ktor. Service guides for S3, DynamoDB, and SQS/SNS lead to runnable examples, while the database and operations guides make ownership and shutdown boundaries explicit.

## Version baseline

Applications select one central BOM version: `io.github.bluetape4k:bluetape4k-dependencies:<version>`. They do not need to coordinate the `bluetape4k-aws`, AWS SDK, and related bluetape4k library versions independently.

The technical baseline of this manual is `bluetape4k-aws 0.4.0`. It covers the 6 published projects and 8 runnable example projects present in that stable tag. Develop-only projects are intentionally excluded.

- Release tag: [`0.4.0`](https://github.com/bluetape4k/bluetape4k-aws/tree/0.4.0)
- Release commit: [`be4e6daea5654f84579955307ec56a58c8f405be`](https://github.com/bluetape4k/bluetape4k-aws/commit/be4e6daea5654f84579955307ec56a58c8f405be)
- Runtime baseline: JDK 21, Kotlin 2.3, Spring Boot 4, and Ktor 3

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

