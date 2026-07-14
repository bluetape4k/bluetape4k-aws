---
manualId: "aws-spring-boot-dynamodb-examples"
id: "aws-spring-boot-dynamodb-examples"
title: "Spring Boot DynamoDB Workshop"
locale: "en"
kind: "example"
gradlePath: ":aws-spring-boot-dynamodb-examples"
sourceDir: "examples/aws-spring-boot-dynamodb-examples"
releaseRef: "0.4.0"
artifact: null
---

# Spring Boot DynamoDB Workshop

> A runnable workshop grounded in the 0.4.0 release source.

## Learning goal {#problem}

Build a Spring Boot 4 WebFlux API on `AbstractCoroutinesDynamoDbRepository`. Learn how auto-configuration supplies enhanced async clients and table naming while repository and controller code remain coroutine-oriented.

## When to use this workshop {#when-to-use}

Use it when a Spring service wants enhanced-client mapping, conditional AWS beans, and context-managed client lifecycle.

## Project coordinates {#coordinates}

This example is not published. Run `./gradlew :aws-spring-boot-dynamodb-examples:test`. Applications import the central BOM, `bluetape4k-aws-spring-boot`, and `software.amazon.awssdk:dynamodb-enhanced`.

## Concepts to learn {#concepts}

`Order` is a `@DynamoDbBean` with `id` as partition key. `OrderRepository` resolves the `orders` table and converts entities and keys. Suspend CRUD and `Flow` scans sit above the enhanced async client.

## Staged walkthrough {#quick-start}

1. Inspect the bean mapping and partition key.
2. Follow `OrderRepository` into the base coroutine repository.
3. Trace POST/GET/DELETE/list controller paths.
4. Run the emulator-backed repository and WebTestClient tests.
5. Run `processAot` after changing configuration or reflection-visible types.

## Entry points and expected behavior {#api-by-task}

`SpringBootDynamoDbExampleApplication` starts the service. `POST /orders` creates a UUID-backed order; `GET /orders/{id}` returns `404` when absent; delete removes one order; list streams a `Flow<Order>`.

## Recommended exercise order {#patterns}

Define keys and access patterns before repository methods. Keep HTTP validation in the controller and item mapping in the DynamoDB model. Treat scan as a teaching tool, not the default production query.

## Integration boundary {#integrations}

Spring auto-configuration creates and closes AWS clients and enhanced clients when required service classes and properties are present. The repository consumes those beans; it does not create transport per request.

## Configuration checkpoints {#configuration}

Enable `bluetape4k.aws.dynamodb`, then provide region and optional endpoint override. Emulator tests inject static credentials. Production uses the deployment credential chain and explicit table ownership.

## Failure modes {#failures}

Missing enhanced-client runtime dependencies, invalid bean mapping, absent tables, wrong endpoint/region, throttling, and scan pagination are the main failure paths.

## Operations {#operations}

Track operation latency, throttles, conditional failures, consumed capacity, and scan volume. Let the Spring context close auto-configured clients; stop accepting requests before context shutdown.

## Testing the boundary {#testing}

`OrderControllerLocalStackTest` selects Floci or LocalStack, wires endpoint and credentials through `ApplicationContextRunner`, and verifies repository CRUD, scans, concurrent operations, and controller behavior with `WebTestClient`.

## Next learning path {#workshops}

Compare the Ktor DynamoDB workshop for explicit plugin setup, then add conditional writes, pagination, and access-pattern-specific queries to the application design.

## Limitations {#limitations}

The workshop uses a single-key table and scan. It does not cover indexes, transactions, optimistic concurrency, production capacity, IAM, or schema migration.

## Sources {#sources}

- [Application and controller](../../../../examples/aws-spring-boot-dynamodb-examples/src/main/kotlin/io/bluetape4k/aws/examples/spring/dynamodb/OrderController.kt)
- [Coroutine repository](../../../../examples/aws-spring-boot-dynamodb-examples/src/main/kotlin/io/bluetape4k/aws/examples/spring/dynamodb/OrderRepository.kt)
- [Emulator-backed test](../../../../examples/aws-spring-boot-dynamodb-examples/src/test/kotlin/io/bluetape4k/aws/examples/spring/dynamodb/OrderControllerLocalStackTest.kt)

