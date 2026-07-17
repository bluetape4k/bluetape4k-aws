---
manualId: "bluetape4k-aws-java"
id: "bluetape4k-aws-java"
title: "AWS SDK for Java v2 Extensions"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-aws-java"
sourceDir: "aws-java"
releaseRef: "0.4.0"
artifact: io.github.bluetape4k.aws:bluetape4k-aws-java
---

# AWS SDK for Java v2 Extensions

> Library manual grounded in the 0.4.0 release source.

## Problem {#problem}

Kotlin builders and sync, `CompletableFuture`, and coroutine APIs over AWS SDK for Java v2 clients.

## When to use it {#when-to-use}

Choose it when the application uses Java SDK v2 clients, needs both blocking and asynchronous paths, or integrates with Spring Boot.

## Coordinates {#coordinates}

Applications select one central BOM version.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-java")
}
```

AWS service SDKs follow a `compileOnly` policy; add the services actually used as runtime dependencies.

## Core concepts {#concepts}

Factories build clients; extensions keep the AWS client type visible; async extensions expose futures; coroutine extensions await those futures. The application owns every created client and must close it.

## Quick start {#quick-start}

```kotlin
val s3 = S3AsyncClient.create()
try {
    s3.putAsByteArray(bucket, key, payload)
    val objects = s3.listAllObjects(bucket).toList()
} finally {
    s3.close()
}
```

## API by task {#api-by-task}

S3 object and transfer helpers, DynamoDB enhanced repositories and batch execution, SQS/SNS messaging, KMS, CloudWatch, Kinesis, SES, STS, and request-model builders.

## Recommended patterns {#patterns}

Put client and background-job ownership at one application boundary. Configure region, credentials, and endpoints once instead of rebuilding them per call.

## Integrations {#integrations}

Spring Boot and Ktor modules build on this library. Add only the Java SDK v2 services used at runtime, for example `software.amazon.awssdk:s3`.

## Configuration {#configuration}

Set region, credentials, endpoint override, HTTP implementation, retry policy, and timeouts on the AWS client builder before sharing the client.

## Failure modes {#failures}

Blank queue URLs, incomplete S3 pagination, missing service jars, blocking calls on coroutine threads, and leaked async clients are the common failure modes.

## Operations {#operations}

Reuse thread-safe clients, bound concurrency, monitor retries and throttling, and make copy-then-delete S3 move semantics explicit.

## Testing {#testing}

Use Floci first with endpoint override and static test credentials; use LocalStack only for service behavior that Floci does not cover.

## Workshops and learning path {#workshops}

Read `client-lifecycle`, then `sync-async-coroutines`, then `service-patterns`; continue with the released S3, DynamoDB, and SQS examples.

## Limitations {#limitations}

The artifact does not bring every AWS service SDK at runtime. Coroutine helpers do not turn synchronous clients into non-blocking clients.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are copied byte-for-byte from README assets in the `0.4.0` release tag. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG source.

### AWS Java architecture diagram

[![AWS Java architecture diagram](../../assets/readme-diagrams/aws-java-architecture-01.png)](../../assets/readme-diagrams/aws-java-architecture-01.svg)

_Release README: [`aws-java/README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/be4e6daea5654f84579955307ec56a58c8f405be/aws-java/README.md)_

### AWS Java operation flow diagram

[![AWS Java operation flow diagram](../../assets/readme-diagrams/aws-java-flow-02.png)](../../assets/readme-diagrams/aws-java-flow-02.svg)

_Release README: [`aws-java/README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/be4e6daea5654f84579955307ec56a58c8f405be/aws-java/README.md)_

### AWS Java coroutine sequence diagram

[![AWS Java coroutine sequence diagram](../../assets/readme-diagrams/aws-java-sequence-03.png)](../../assets/readme-diagrams/aws-java-sequence-03.svg)

_Release README: [`aws-java/README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/be4e6daea5654f84579955307ec56a58c8f405be/aws-java/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [Release source: `aws-java/src/main/kotlin/io/bluetape4k/aws/s3/S3AsyncClientCoroutinesExtensions.kt`](../../../../aws-java/src/main/kotlin/io/bluetape4k/aws/s3/S3AsyncClientCoroutinesExtensions.kt)
- [Release source: `aws-java/src/main/kotlin/io/bluetape4k/aws/dynamodb/repository/DynamoDbCoroutineRepository.kt`](../../../../aws-java/src/main/kotlin/io/bluetape4k/aws/dynamodb/repository/DynamoDbCoroutineRepository.kt)
- [Release test: emulator selection](../../../../aws-java/src/test/kotlin/io/bluetape4k/aws/AbstractAwsTest.kt)
