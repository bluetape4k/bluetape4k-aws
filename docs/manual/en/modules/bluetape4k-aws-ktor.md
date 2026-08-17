---
manualId: "bluetape4k-aws-ktor"
id: "bluetape4k-aws-ktor"
title: "AWS Ktor Integration"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-aws-ktor"
sourceDir: "aws-ktor"
releaseRef: "0.5.0"
artifact: io.github.bluetape4k.aws:bluetape4k-aws-ktor
---

# AWS Ktor Integration

> Library manual grounded in the 0.5.0 release source.

## Problem {#problem}

Ktor 3 client signing plus server plugins and runtimes for S3, DynamoDB, SQS, Exposed, CloudWatch, IMDS, Access Grants, and S3 Vectors.

## When to use it {#when-to-use}

Use it when a Ktor application needs coroutine-native AWS integration without adopting Spring's lifecycle model.

## Coordinates {#coordinates}

Applications select one central BOM version.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-ktor")
}
```

`aws-ktor` is an API aggregator, so its published POM exposes the Java/Kotlin
wrappers, Ktor client core, and the AWS SDK modules used by its public plugins.
For this module, the generated POM is the source of truth:

| Category | Published scope | Application action |
| --- | --- | --- |
| Bluetape wrappers, Ktor core, and public Java SDK types | `compile` | Add `bluetape4k-aws-ktor`; do not repeat the wrapper or service SDK just to install an `aws-ktor` plugin |
| AWS Kotlin DynamoDB public types | `compile` | `DynamoDbKtorPlugin` needs no separate Kotlin DynamoDB SDK declaration |
| Ktor engines, Jackson, Micrometer, Exposed, JDBC, and other runtime choices | `compileOnly` or application-owned | Add only the integration and driver the application actually installs |

The general `compileOnly` rule still applies to the lower-level
`bluetape4k-aws-java` and `bluetape4k-aws-kotlin` wrapper modules. Do not carry
that rule over to the `aws-ktor` aggregator without checking its current
`build.gradle.kts` and generated POM. Add a service SDK directly only when
application code calls that SDK outside the `aws-ktor` plugin API.

The choice follows the plugin API: `SqsConsumer` and the other Java-service
plugins use AWS SDK for Java v2, while `DynamoDbKtorPlugin` uses the AWS Kotlin
SDK. Both choices are already represented by the aggregator's transitive
dependencies.

## Core concepts {#concepts}

`AwsSigV4Plugin` signs outbound Ktor client requests. Application plugins create typed runtimes, store them in attributes, start background jobs, and close owned resources on application stop.

## Quick start {#quick-start}

```kotlin
install(SqsConsumer) {
    queueUrl = config.queueUrl
    deleteOnSuccess = true
    onMessage<OrderMessage> { message -> process(message) }
}
```

## API by task {#api-by-task}

SigV4 client auth, S3 REST and encryption helpers, DynamoDB repository runtime, SQS consumer, Exposed database plugin, CloudWatch/Logs, IMDS, Access Grants, and S3 Vectors.

## Recommended patterns {#patterns}

Put client and background-job ownership at one application boundary. Configure region, credentials, and endpoints once instead of rebuilding them per call.

## Integrations {#integrations}

Add this library through `bluetape4k-dependencies`, then add only application-
owned runtime integrations. The Java SQS and Kotlin DynamoDB SDK types used by
the built-in plugins are already published transitively; direct SDK calls may
still declare their service module explicitly.

## Configuration {#configuration}

Keep region, service, credential provider, signing options, queue polling, concurrency, endpoint override, and shutdown timeout in application configuration.

## Failure modes {#failures}

Wrong SigV4 service/region, consumed request bodies, clock skew, missing service SDKs, duplicate plugin installation, and uncoordinated coroutine shutdown are common faults.

## Operations {#operations}

Use structured application scopes, bound consumers, expose Micrometer observations, and ensure plugins stop before shared clients are closed.

## Testing {#testing}

Use Ktor `testApplication`, deterministic credentials and clocks for signing, and Floci for service runtimes. Assert stop hooks leave no jobs or clients running.

## Workshops and learning path {#workshops}

Read `client-and-sigv4`, then `service-plugins`, then `runtime-lifecycle`; run the released Ktor S3, DynamoDB, SQS, and Exposed examples.

## Limitations {#limitations}

The Ktor REST helpers do not replace the full AWS SDK surface, and installing a plugin does not provision AWS resources.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `0.5.0` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### AWS Ktor Architecture

[![AWS Ktor Architecture](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-ktor-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-ktor-architecture-01.svg)

_Release README: [`aws-ktor/README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-ktor/README.md)_

### Ktor S3 Access Grants flow

[![Ktor S3 Access Grants flow](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-ktor-s3-access-grants-flow-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-ktor-s3-access-grants-flow-01.svg)

_Release README: [`aws-ktor/README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-ktor/README.md)_

### Advanced S3 helper architecture

[![Advanced S3 helper architecture](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-ktor-s3-advanced-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-ktor-s3-advanced-architecture-01.svg)

_Release README: [`aws-ktor/README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-ktor/README.md)_

### Advanced S3 upload/load sequence

[![Advanced S3 upload/load sequence](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-ktor-s3-advanced-sequence-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-ktor-s3-advanced-sequence-01.svg)

_Release README: [`aws-ktor/README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-ktor/README.md)_

### SQS Consumer And Publisher diagram

[![SQS Consumer And Publisher diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-ktor-sequence-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-ktor-sequence-01.svg)

_Release README: [`aws-ktor/README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-ktor/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [Release source: `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/client/AwsSigV4Plugin.kt`](../../../../aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/client/AwsSigV4Plugin.kt)
- [Release source: `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerPlugin.kt`](../../../../aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerPlugin.kt)
- [Release test: SQS runtime failure handling](../../../../aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerRuntimeFailureTest.kt)
