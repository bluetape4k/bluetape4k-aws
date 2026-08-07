---
manualId: "repository-map"
title: "AWS Repository Map"
locale: "en"
releaseRef: "0.5.0"
---

# AWS Repository Map

The `0.5.0` release contains 14 Gradle projects. Six are published libraries or platforms; eight are runnable examples. Read them as a set of layers rather than an alphabetical catalog.

![AWS repository module map](../../assets/overview/repository-module-map.png)

## Layer 1: version alignment

`bluetape4k-aws-bom` aligns published artifacts from this repository. Application builds normally import the broader `bluetape4k-dependencies` BOM instead, because that is the consumer-facing version boundary across bluetape4k repositories.

## Layer 2: SDK foundations

| Project | SDK model | Use it for |
| --- | --- | --- |
| `bluetape4k-aws-java` | AWS SDK for Java v2 | Sync helpers, `CompletableFuture` extensions, suspending adapters, enhanced DynamoDB repositories, S3 transfer, and broad Java SDK service coverage |
| `bluetape4k-aws-kotlin` | AWS SDK for Kotlin | Native `suspend` clients, request DSLs, DynamoDB batch work, S3 helpers, and Kotlin-native service access |

These are alternative foundations for most application code. A framework integration may use both internally—for example, Java SDK SQS and Kotlin SDK DynamoDB—but an application should still define ownership for every client and HTTP engine.

## Layer 3: database bridge

`bluetape4k-aws-exposed` resolves AWS-backed connection settings, optionally creates RDS IAM authentication tokens, builds Hikari data sources, connects Exposed JDBC databases, and groups default and named handles in a closeable registry. It does not own transactions or AWS client lifecycle.

## Layer 4: application frameworks

- `bluetape4k-aws-spring-boot` binds `bluetape4k.aws.*` properties and creates conditional clients, templates, repositories, listeners, and database registries. Spring owns beans created by the auto-configuration and closes them with the application context.
- `bluetape4k-aws-ktor` provides Ktor plugins and runtime objects for SigV4, S3, SQS, DynamoDB, CloudWatch, IMDS, S3 Access Grants, S3 Vectors, and Exposed. Plugin-created resources are stopped with the Ktor application; injected clients remain application-owned.

## Layer 5: runnable learning projects

| Goal | Ktor example | Spring Boot example |
| --- | --- | --- |
| S3 object HTTP API | `aws-ktor-s3-examples` | `aws-spring-boot-s3-examples` |
| DynamoDB repository | `aws-ktor-dynamodb-examples` | `aws-spring-boot-dynamodb-examples` |
| SQS processing and SNS fanout | `aws-ktor-sqs-examples` | `aws-spring-boot-sqs-examples` |
| Exposed JDBC with AWS settings | `aws-ktor-exposed-examples` | `aws-spring-boot-exposed-examples` |

Examples are not published artifacts. They are copy points for configuration, application boundaries, and emulator-backed tests. Read an example together with its library module; copying only a route or controller omits the lifecycle and dependency decisions around it.

## Release scope rule

This map includes only projects registered by `settings.gradle.kts` in tag `0.5.0`. Projects added later on `develop` belong to a later manual baseline even if their source is already visible in the repository.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `0.5.0` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### Overview diagram

[![Overview diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-architecture-01.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

### Three-Tier API (bluetape4k-aws-java module — Java SDK v2) diagram

[![Three-Tier API (bluetape4k-aws-java module — Java SDK v2) diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-architecture-02.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-architecture-02.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

### Native Suspend (bluetape4k-aws-kotlin module — Kotlin SDK) diagram

[![Native Suspend (bluetape4k-aws-kotlin module — Kotlin SDK) diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-architecture-03.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-architecture-03.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

### CloudWatch metrics and logs components

[![CloudWatch metrics and logs components](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-cloudwatch-components-12.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-cloudwatch-components-12.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

### CloudWatch metrics DSL support map

[![CloudWatch metrics DSL support map](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-cloudwatch-components-30.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-cloudwatch-components-30.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

### CloudWatch publish flow

[![CloudWatch publish flow](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-cloudwatch-flow-13.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-cloudwatch-flow-13.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

### CloudWatch metrics publish and list flow

[![CloudWatch metrics publish and list flow](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-cloudwatch-flow-31.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-cloudwatch-flow-31.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

### AWS component map diagram

[![AWS component map diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-components-04.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-components-04.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

### DynamoDB coroutine repository components

[![DynamoDB coroutine repository components](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-dynamodb-components-10.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-dynamodb-components-10.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

### DynamoDB native suspend support map

[![DynamoDB native suspend support map](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-dynamodb-components-28.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-dynamodb-components-28.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

### DynamoDB coroutine repository flow

[![DynamoDB coroutine repository flow](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-dynamodb-flow-11.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-dynamodb-flow-11.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

### DynamoDB suspend item and batch flow

[![DynamoDB suspend item and batch flow](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-dynamodb-flow-29.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-dynamodb-flow-29.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

### Secrets Manager and Parameter Store environment sources

[![Secrets Manager and Parameter Store environment sources](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-env-sources-components-16.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-env-sources-components-16.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

### Secrets Manager and Parameter Store property key mapping

[![Secrets Manager and Parameter Store property key mapping](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-env-sources-flow-17.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-env-sources-flow-17.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

### EC2 IMDS access surfaces

[![EC2 IMDS access surfaces](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-imds-components-14.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-imds-components-14.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

### EC2 IMDS metadata flow

[![EC2 IMDS metadata flow](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-imds-flow-15.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-imds-flow-15.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

### KMS Spring Boot components

[![KMS Spring Boot components](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-kms-components-06.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-kms-components-06.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

### KMS Spring Boot support map

[![KMS Spring Boot support map](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-kms-components-20.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-kms-components-20.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

### KMS encrypt and decrypt flow

[![KMS encrypt and decrypt flow](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-kms-flow-07.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-kms-flow-07.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

### KMS operations flow

[![KMS operations flow](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-kms-flow-21.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-kms-flow-21.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

### S3 Access Grants components

[![S3 Access Grants components](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-s3-access-grants-components-08.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-s3-access-grants-components-08.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

### S3 Access Grants flow

[![S3 Access Grants flow](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-s3-access-grants-flow-09.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-s3-access-grants-flow-09.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

### S3 coroutine support map

[![S3 coroutine support map](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-s3-components-24.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-s3-components-24.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

### S3 coroutine operation flow

[![S3 coroutine operation flow](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-s3-flow-25.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-s3-flow-25.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

### AWS service coverage chart

[![AWS service coverage chart](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

### SNS Spring Boot support map

[![SNS Spring Boot support map](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-sns-components-22.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-sns-components-22.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

### SNS publish and HTTP endpoint flow

[![SNS publish and HTTP endpoint flow](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-sns-flow-23.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-sns-flow-23.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

### SQS Spring Boot runtime

[![SQS Spring Boot runtime](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-sqs-components-18.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-sqs-components-18.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

### SQS coroutine support map

[![SQS coroutine support map](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-sqs-components-26.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-sqs-components-26.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

### SQS listener flow

[![SQS listener flow](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-sqs-flow-19.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-sqs-flow-19.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

### SQS coroutine message flow

[![SQS coroutine message flow](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-sqs-flow-27.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bluetape4k-aws-sqs-flow-27.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

### Bluetape4k AWS module composition chart

[![Bluetape4k AWS module composition chart](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/root-readme-module-chart-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/root-readme-module-chart-01.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

### Bluetape4k AWS overview diagram

[![Bluetape4k AWS overview diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/root-readme-overview-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/root-readme-overview-01.svg)

_Release README: [`README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/README.md)_

<!-- release-readme-diagrams:end -->

## Sources

- [Gradle project registry](../../../../settings.gradle.kts)
- [Published AWS platform](../../../../bom/build.gradle.kts)
- [Repository module overview](../../../../README.md)
