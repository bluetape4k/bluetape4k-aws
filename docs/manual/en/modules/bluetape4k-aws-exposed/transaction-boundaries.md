---
title: Transaction boundaries
description: Separate AWS configuration, pool ownership, and Exposed business transactions.
manualId: bluetape4k-aws-exposed
chapterId: transaction-boundaries
---

# Transaction boundaries

`AwsExposedDatabaseFactory` creates infrastructure; it does not choose business transaction boundaries. The service that combines reads and writes owns the Exposed transaction.

## Registry ownership

A factory creates a default handle and optional named handles. Each handle contains an Exposed `Database` and its data source. The registry closes every created handle, including partial startup cleanup when later database creation fails.

## Keep AWS calls outside JDBC transactions

Resolve secrets and create pools at startup. Do not fetch Secrets Manager values or generate remote configuration inside every transaction. Holding a JDBC connection while waiting on an AWS network call consumes pool capacity without database progress.

```kotlin
suspend fun createOrder(command: CreateOrder): OrderRecord =
    withContext(Dispatchers.IO) {
        transaction(registry.default.database) {
            Orders.insertAndGetId { /* map command */ }
            // Materialize the result before leaving this block.
        }
    }
```

JDBC is blocking. Use the framework's configured I/O or transaction context. A coroutine wrapper changes scheduling, not the driver protocol.

## Retry and side effects

Let exceptions escape the transaction so Exposed can roll back. Retrying the whole block repeats every side effect inside it, so publish messages after commit or use an outbox. Do not keep an Exposed DAO entity after the transaction closes.

## Named databases

Choose the handle before starting the transaction. Two handles mean two independent connections and commits; this library does not provide distributed transactions.

## Testing

Assert rollback, handle close, named lookup, partial-registry cleanup, and pool release. For IAM, inject a fake token generator and a controllable clock.

## Sources

- [Database factory](../../../../../aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsExposedDatabaseFactory.kt)
- [Database handle](../../../../../aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsExposedDatabaseHandle.kt)
- [Database registry](../../../../../aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsExposedDatabaseRegistry.kt)

