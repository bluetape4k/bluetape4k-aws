---
manualId: "aws-ktor-dynamodb-examples"
id: "aws-ktor-dynamodb-examples"
title: "Ktor DynamoDB Workshop"
locale: "en"
kind: "example"
gradlePath: ":aws-ktor-dynamodb-examples"
sourceDir: "examples/aws-ktor-dynamodb-examples"
releaseRef: "0.5.0"
artifact: null
---

# Ktor DynamoDB Workshop

> A runnable workshop grounded in the 0.5.0 release source.

## Learning goal {#problem}

Build a Ktor 3 CRUD API backed by the AWS Kotlin SDK DynamoDB client. The exercise keeps table definition, item mapping, repository access, HTTP behavior, and emulator wiring visible in one source file.

## When to use this workshop {#when-to-use}

Use it before adding DynamoDB to a Ktor service or when comparing the Ktor plugin with Spring's enhanced-client repository. It is most useful when partition-key design and client ownership are already explicit.

## Project coordinates {#coordinates}

This example is not published. Run it from the repository with `./gradlew :aws-ktor-dynamodb-examples:test`. Applications import `bluetape4k-dependencies`, `bluetape4k-aws-ktor`, and the AWS Kotlin DynamoDB service module.

## Concepts to learn {#concepts}

`DynamoDbKtorPlugin` owns a plugin-created client, optionally creates only registered tables, and exposes a coroutine repository. `Order` uses `id` as the partition key; `DynamoItemMapper` and `DynamoItemReader` keep domain-to-attribute conversion explicit.

## Staged walkthrough {#quick-start}

1. Read `Order`, its mapper, and reader before the routes.
2. Inspect the `orders` table definition with `BillingMode.PayPerRequest`.
3. Follow `dynamoDbExampleModule` from plugin installation to the four CRUD routes.
4. Run the test with Floci, then repeat with LocalStack only when fallback coverage is needed.

## Entry points and expected behavior {#api-by-task}

`DynamoDbExampleRoutes.kt` is the application entry point. `POST /dynamodb/orders` rejects blank IDs or status with `400`; `GET /dynamodb/orders/{id}` returns `404` when absent; delete removes one key; list scans and returns all orders.

## Recommended exercise order {#patterns}

First prove one put/get round trip. Add validation and not-found behavior next. Only then inspect scan and table auto-creation, because neither should hide an unclear key model.

## Integration boundary {#integrations}

The Ktor plugin connects route code to `bluetape4k-aws-kotlin`. The host application supplies emulator endpoint, region, and credentials; an injected client remains application-owned.

## Configuration checkpoints {#configuration}

Pass `endpointUrl`, `region`, and `credentialsProvider` into `dynamoDbExampleModule`. In production, remove emulator endpoints, use the deployment credential chain, and manage table schema outside application startup unless auto-creation is intentional.

## Failure modes {#failures}

Expect failures from blank keys, mismatched attribute mapping, an absent table, wrong region/endpoint, or credentials that cannot create or access the table. A scan that works locally is not evidence of a scalable production access pattern.

## Operations {#operations}

Observe operation latency, throttling, conditional failures, and consumed capacity. Keep table creation and migration ownership separate from request handling, and close application-owned clients after Ktor plugins stop.

## Testing the boundary {#testing}

`DynamoDbExampleRoutesLocalStackTest` selects Floci by default and supports `-Dbluetape4k.aws.emulator=localstack`. It verifies table setup and route-level create, read, delete, list, validation, and not-found behavior through the real SDK transport.

## Next learning path {#workshops}

Continue to the Spring Boot DynamoDB workshop to compare enhanced-client mapping and auto-configuration, or move to the SQS workshops to learn long-running consumer lifecycle.

## Limitations {#limitations}

The workshop uses one partition key and a scan endpoint. It does not teach secondary indexes, conditional concurrency control, pagination, transactions, IAM policy, or production capacity design.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `0.5.0` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### aws ktor dynamodb examples Architecture diagram

[![aws ktor dynamodb examples Architecture diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/examples-aws-ktor-dynamodb-examples-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/examples-aws-ktor-dynamodb-examples-architecture-01.svg)

_Release README: [`examples/aws-ktor-dynamodb-examples/README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/examples/aws-ktor-dynamodb-examples/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [Routes, table, mapper, and repository](../../../../examples/aws-ktor-dynamodb-examples/src/main/kotlin/io/bluetape4k/aws/examples/ktor/dynamodb/DynamoDbExampleRoutes.kt)
- [Emulator-backed route test](../../../../examples/aws-ktor-dynamodb-examples/src/test/kotlin/io/bluetape4k/aws/examples/ktor/dynamodb/DynamoDbExampleRoutesLocalStackTest.kt)
- [Example notes](../../../../examples/aws-ktor-dynamodb-examples/README.md)

