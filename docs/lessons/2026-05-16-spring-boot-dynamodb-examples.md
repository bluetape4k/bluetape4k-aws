---
name: spring-boot-dynamodb-examples
description: Lessons from implementing the aws-spring-boot-dynamodb-examples module (issue #14)
metadata:
  type: project
---

# Spring Boot DynamoDB Examples Module (Issue #14)

## Summary

Added `examples/aws-spring-boot-dynamodb-examples` — a Spring Boot 4 application demonstrating
DynamoDB CRUD via `AbstractCoroutinesDynamoDbRepository`. Covers save, findById, scan, and delete
with `ApplicationContextRunner`-based integration tests backed by LocalStack.

## Root Cause / Context

Pre-release gap: the `aws-spring-boot` DynamoDB auto-configuration had no end-to-end example showing
how to build a repository and test it with `ApplicationContextRunner`.

## Key Decisions

### Repository base class

`OrderRepository` extends `AbstractCoroutinesDynamoDbRepository<Order, String>`. Override
`tableName`, `keyFromId`, and `keyFromItem`. Entity annotated with `@DynamoDbBean`; partition key
getter annotated with `@get:DynamoDbPartitionKey`.

### ApplicationContextRunner wiring

```kotlin
ApplicationContextRunner()
    .withConfiguration(AutoConfigurations.of(AwsAutoConfiguration::class.java, DynamoDbAutoConfiguration::class.java))
    .withBean(AwsCredentialsProvider::class.java, { localStack.getCredentialProvider() })
    .withPropertyValues(
        "bluetape4k.aws.dynamodb.region=${localStack.regionName}",
        "bluetape4k.aws.dynamodb.endpoint-override=${localStack.awsEndpoint}",
    )
```

### Test structure

- `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` on the test class.
- `contextRunner().run { context -> ... }` is blocking — use `runSuspendIO {}` INSIDE the lambda.
- Table creation via `DynamoDbAsyncClient.createTable(...).await()` with ACTIVE status polling.
- Concurrent test uses `SuspendedJobTester().workers(4).rounds(3).add {...}.run()` inside
  `runSuspendIO {}`.

### Assertion style

Use bluetape4k assertions: `shouldBeEqualTo`, `shouldBeTrue`, `shouldBeNull`. Never use plain
`assert()`.

### Table management

Each test may call `createOrdersTable(asyncClient, tableName)` which is idempotent (checks for
`ResourceNotFoundException` and skips creation if the table already exists). Waits up to 30s for
`TableStatus.ACTIVE`.

## Pitfalls Avoided

- Do NOT call `runSuspendIO {}` at the outer test function level — `contextRunner().run {}` is
  blocking and must be the outermost scope.
- Use `runSuspendIO {}` only INSIDE the `contextRunner().run { context -> ... }` lambda.

## Verification

- CRUD test: save → findById → scan → deleteById → findById (null).
- Concurrent test: 4 workers × 3 rounds, each saving a unique order and verifying findById.

## Future Guidance

- When extending Spring Boot DynamoDB examples, follow the same `ApplicationContextRunner` pattern.
- The `LocalStackServer.Launcher.getLocalStack("dynamodb")` + `getCredentialProvider()` pattern is
  the canonical LocalStack wiring for Spring Boot tests.
