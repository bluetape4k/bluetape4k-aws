---
manualId: "learning-path"
title: "Bluetape4k AWS Learning Path"
locale: "en"
releaseRef: "0.5.0"
---

# Bluetape4k AWS Learning Path

The manual contains detailed selection rules, runtime boundaries, and runnable examples. You do not need to read every module in order. Enter at the first unresolved decision, complete one end-to-end path, and then add services or framework features.

## 1. Establish the dependency boundary

Read [Getting started](../getting-started.md). Import `bluetape4k-dependencies` once and add only the wrapper and AWS service modules required by the application. Confirm that no code depends on an AWS service artifact that exists only because tests or another framework pulled it transitively.

## 2. Choose the SDK model

Use [SDK selection](sdk-selection.md) to compare Java SDK v2 and AWS SDK for Kotlin by interoperability, async model, service coverage, transport, and ownership. Build one small client call and close its resources before adding a framework.

## 3. Complete one service path

Follow [AWS service learning paths](service-learning-paths.md):

- S3: object lifecycle, application route, emulator test, then presigning or encryption;
- DynamoDB: key design, repository, conditional behavior, then table lifecycle;
- SQS/SNS: acknowledgement, visibility, idempotency, redelivery, then fanout.

Each path points to Ktor and Spring Boot examples with detailed explanations and tests. Read the example README, source, and test together; the lifecycle and configuration decisions often live outside the controller or route.

## 4. Add the framework integration

Read [Spring Boot or Ktor](spring-vs-ktor.md). Choose the framework that already owns application configuration and shutdown. If neither integration fits, use the SDK foundation directly instead of bringing in a second framework lifecycle.

## 5. Connect relational storage when needed

Read [AWS-backed configuration to Exposed JDBC](database-with-exposed.md) for Secrets Manager or Parameter Store descriptors, optional RDS IAM authentication, Hikari/Exposed database creation, named registries, and transaction ownership. Continue to the [`bluetape4k-exposed` manual](https://bluetape4k.github.io/manual/bluetape4k-exposed/) for repository and transaction patterns.

## 6. Prove operations and shutdown

Finish with [Testing and operations](testing-and-operations.md) and [runtime boundaries](../architecture/runtime-boundaries.md). Run the smallest relevant example against Floci, add an explicit LocalStack fallback only where needed, then test failure, retry, duplicate delivery, and shutdown behavior.

At this point the application should be able to answer four questions for every AWS integration: who creates the client, who closes it, what a retry can repeat, and which test proves the real boundary.

## Sources

- [Representative released example](../../../../examples/aws-ktor-s3-examples/README.md)
- [Release project registry](../../../../settings.gradle.kts)
