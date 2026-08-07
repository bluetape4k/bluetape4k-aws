---
manualId: "aws-spring-boot-exposed-examples"
id: "aws-spring-boot-exposed-examples"
title: "Spring Boot Exposed Workshop"
locale: "en"
kind: "example"
gradlePath: ":aws-spring-boot-exposed-examples"
sourceDir: "examples/aws-spring-boot-exposed-examples"
releaseRef: "0.5.0"
artifact: null
---

# Spring Boot Exposed Workshop

> A runnable workshop grounded in the 0.5.0 release source.

## Learning goal {#problem}

Connect Spring Boot 4 MVC to an AWS-configurable Hikari/Exposed JDBC database while keeping HTTP status, transaction ownership, schema setup, and query code in separate components.

## When to use this workshop {#when-to-use}

Use it when a Spring application needs Exposed JDBC and wants database settings to be replaceable by Secrets Manager, Parameter Store, environment values, or RDS IAM.

## Project coordinates {#coordinates}

This example is not published. Run `./gradlew :aws-spring-boot-exposed-examples:test`. Applications use the central BOM with `bluetape4k-aws-spring-boot`, `bluetape4k-aws-exposed`, Exposed JDBC, HikariCP, and a driver.

## Concepts to learn {#concepts}

`OrderController` owns HTTP semantics, `OrderService` owns `transaction(database)`, `OrderRepository` owns Exposed queries, and `OrderSchemaInitializer` creates the table after the auto-configured `Database` is available.

## Staged walkthrough {#quick-start}

1. Read the table and repository mapping.
2. Follow controller calls into service-owned transactions.
3. Inspect auto-configured registry, data source, and `Database` beans.
4. Run the PostgreSQL Testcontainers test.
5. Replace direct test settings with an application configuration source without moving secret reads into repositories.

## Entry points and expected behavior {#api-by-task}

`SpringBootExposedExampleApplication` starts the service. `POST /orders` returns `201`; `GET /orders/{id}` returns one order or `404`; list accepts an optional `customerId` filter.

## Recommended exercise order {#patterns}

Keep one transaction per business operation and keep repositories unaware of Spring and AWS clients. Apply schema migrations before readiness rather than scattering DDL across request paths.

## Integration boundary {#integrations}

`AwsExposedAutoConfiguration` creates a closeable registry and exposes default `DataSource` and `Database` beans. Spring owns those resources; Exposed repositories use an already active transaction.

## Configuration checkpoints {#configuration}

Set JDBC URL, driver, username, password, and Hikari pool limits under `bluetape4k.aws.exposed.default-database`. Production sources may resolve these values before bean creation.

## Failure modes {#failures}

Driver mismatch, bad credentials, unavailable configuration source, pool exhaustion, migration failure, and repository calls outside a transaction are the main failures.

## Operations {#operations}

Monitor pool acquisition, active/idle connections, transaction errors, and query latency. Refresh expiring credentials through a deliberate pool replacement and let Spring close the registry once.

## Testing the boundary {#testing}

`SpringBootExposedExampleApplicationTest` starts PostgreSQL, verifies registry/data source/database beans, and covers HTTP create, read, list, filtering, and not-found behavior on a random port.

## Next learning path {#workshops}

Compare the Ktor Exposed workshop for plugin-managed transactions, then continue with the bluetape4k-exposed manual for repository and transaction patterns.

## Limitations {#limitations}

The test does not call AWS configuration services or RDS IAM and does not prove TLS, migration tooling, credential rotation, or production pool sizing.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `0.5.0` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### aws spring boot exposed examples Architecture diagram

[![aws spring boot exposed examples Architecture diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/examples-aws-spring-boot-exposed-examples-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/examples-aws-spring-boot-exposed-examples-architecture-01.svg)

_Release README: [`examples/aws-spring-boot-exposed-examples/README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/examples/aws-spring-boot-exposed-examples/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [Controller](../../../../examples/aws-spring-boot-exposed-examples/src/main/kotlin/io/bluetape4k/aws/examples/spring/exposed/OrderController.kt)
- [Transaction-owning service](../../../../examples/aws-spring-boot-exposed-examples/src/main/kotlin/io/bluetape4k/aws/examples/spring/exposed/OrderService.kt)
- [PostgreSQL integration test](../../../../examples/aws-spring-boot-exposed-examples/src/test/kotlin/io/bluetape4k/aws/examples/spring/exposed/SpringBootExposedExampleApplicationTest.kt)

