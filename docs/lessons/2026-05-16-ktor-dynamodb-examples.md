---
name: ktor-dynamodb-examples
description: Lessons from implementing the aws-ktor-dynamodb-examples module (issue #17)
metadata:
  type: project
---

# Ktor DynamoDB Examples Module (Issue #17)

## Summary

Added `examples/aws-ktor-dynamodb-examples` — a Ktor 3 application demonstrating DynamoDB CRUD via
the `aws-ktor` `DynamoDbKtorPlugin`. Covers save, findById, scan, and delete via HTTP routes backed
by LocalStack integration tests using the AWS Kotlin SDK.

## Root Cause / Context

Pre-release gap: the `aws-ktor` DynamoDB integration had no end-to-end example showing how to wire up
the `DynamoDbKtorPlugin` inside a Ktor application with full CRUD and concurrent access tests.

## Key Decisions

### DynamoDbKtorPlugin wiring

```kotlin
install(DynamoDbKtorPlugin) {
    region = regionName
    endpointUrl = endpointUrl
    credentialsProvider = provider
    autoCreateTables = true
    table("orders") { /* schema */ }
}
```

Access repository via `application.dynamoDb().repository("orders", mapper, reader, keyMapper)`.

### AWS Kotlin SDK credentials

```kotlin
val credentialsProvider = StaticCredentialsProvider {
    accessKeyId = localStack.accessKey
    secretAccessKey = localStack.secretKey
}
val endpointUrl = Url.parse(localStack.endpoint.toString())
```

### Test structure

- `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` on the test class.
- Private `testModule(block)` helper wraps `testApplication {}` with `dynamoDbExampleModule(...)`.
- Each `@Test` calls `testModule { ... }` directly as the test return value (not `runSuspendIO`).
- JSON content negotiation via Jackson: `createClient { install(ContentNegotiation) { jackson() } }`.
- Concurrent test uses `SuspendedJobTester().workers(4).rounds(3).add {...}.run()` inside
  `testModule {}`.

### Assertion style

Use bluetape4k assertions: `shouldBeEqualTo`, `shouldBeTrue`. HTTP status comparisons use
`status shouldBeEqualTo HttpStatusCode.Created` etc.

## Pitfalls Avoided

- Do NOT wrap `testApplication {}` inside `runSuspendIO {}`.
- `testModule` helper must return the result of `testApplication {}` directly.

## Verification

Routes tested:
- `POST /dynamodb/orders` → 201 Created
- `GET /dynamodb/orders/{id}` → 200 OK or 404 Not Found
- `GET /dynamodb/orders` → 200 OK (list)
- `DELETE /dynamodb/orders/{id}` → 204 No Content

Concurrent test: 4 workers × 3 rounds, each saving a unique order and immediately finding it by id.

## Future Guidance

- When extending DynamoDB Ktor examples, add routes and corresponding test cases here.
- The `Url.parse(localStack.endpoint.toString())` + `StaticCredentialsProvider` pattern is the
  canonical AWS Kotlin SDK LocalStack wiring.
