---
title: Sync, async, and coroutine APIs
description: Choose deliberately between blocking, CompletableFuture, and suspend AWS calls.
manualId: bluetape4k-aws-java
chapterId: sync-async-coroutines
---

# Sync, async, and coroutine APIs

The Java SDK v2 extension layer deliberately keeps three calling models visible. Pick the model at the application boundary instead of wrapping every call in a coroutine indiscriminately.

## The three tiers

- Sync extensions call a synchronous AWS client and block until the response arrives.
- Async extensions return `CompletableFuture` and compose naturally with Java async code.
- Coroutine extensions call the async client and suspend while awaiting the future.

```kotlin
suspend fun copyAndList(
    s3: S3AsyncClient,
    bucket: String,
    key: String,
    bytes: ByteArray,
) {
    s3.putAsByteArray(bucket, key, bytes)
    s3.listAllObjects(bucket).collect { objectSummary -> use(objectSummary) }
}
```

## Cancellation is cooperative

Cancelling a coroutine stops waiting for the future, but remote AWS work may already be in progress. Do not promise atomic cancellation for multi-request operations. Design writes with idempotency keys or safe retries and observe completion/failure separately.

## Flow and pagination

Pagination helpers such as `listAllObjects` return a cold `Flow`. No request occurs until collection. Collection performs page requests sequentially and follows continuation tokens. Bound downstream work with explicit concurrency instead of collecting an unbounded service listing into memory.

## Error semantics

AWS SDK service exceptions remain visible. Translate them at the application boundary where retryability and domain meaning are known. Preserve request IDs in logs, but never log credentials, KMS plaintext, or secret payloads.

## Selection rule

Use sync APIs in a dedicated blocking boundary, futures for Java composition, and coroutine extensions in suspend-first Kotlin services. Do not put a sync SDK call on `Dispatchers.Default`; use an async client or an explicit I/O boundary.

## Sources

- [S3 coroutine extensions](../../../../../aws-java/src/main/kotlin/io/bluetape4k/aws/s3/S3AsyncClientCoroutinesExtensions.kt)
- [SQS async extensions](../../../../../aws-java/src/main/kotlin/io/bluetape4k/aws/sqs/SqsAsyncClientExtensions.kt)
- [Coroutine bridge](../../../../../aws-java/src/main/kotlin/io/bluetape4k/aws/coroutines/AwsCoroutineSupport.kt)

