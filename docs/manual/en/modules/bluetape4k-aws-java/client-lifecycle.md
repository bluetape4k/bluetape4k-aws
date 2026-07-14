---
title: Client lifecycle and ownership
description: Choose, share, and close AWS SDK v2 clients with explicit application ownership.
manualId: bluetape4k-aws-java
chapterId: client-lifecycle
---

# Client lifecycle and ownership

AWS SDK v2 clients are thread-safe after construction. Build one client for an application scope, reuse it, and close it exactly once after every background worker that uses it has stopped. The factory objects in this module configure clients; they do not move ownership away from the caller.

## Choose the client shape

| Client | Use it for | Calling model |
|---|---|---|
| Service sync client | bounded blocking work | direct return value |
| Service async client | concurrent I/O and coroutine adapters | `CompletableFuture` |
| Transfer Manager | multipart and large S3 transfers | async transfer handle |

A coroutine does not make a synchronous client non-blocking. Prefer an async client when the call must suspend without holding a platform thread.

## One owner, many borrowers

```kotlin
class AwsClients : AutoCloseable {
    val s3: S3AsyncClient = S3ClientFactory.Async.create(region = region)

    override fun close() {
        s3.close()
    }
}
```

Inject this owner or its client into repositories and handlers. Borrowers must not close a shared client. If a framework creates the client, its application lifecycle closes it; if the application injects a client into a Ktor runtime, the application remains the owner.

## Endpoint and credentials

Resolve region and credentials when constructing the client. Endpoint override is useful for Floci or LocalStack, but it still needs a region because AWS signing scopes include it. Never create a default credential provider or HTTP client per request.

## Shutdown order

1. Stop accepting new work.
2. Stop pollers, listeners, and transfer submissions.
3. Wait for in-flight work within a bounded timeout.
4. Close service clients.
5. Close shared HTTP transports only if the application owns them.

Closing the client first turns graceful shutdown into connection and cancellation errors.

## Failure checklist

- A missing service SDK jar fails before client creation.
- A leaked async client leaves event-loop threads and connections alive.
- Closing an injected client from a repository breaks unrelated callers.
- Rebuilding clients per request loses connection pooling and increases credential lookup cost.

## Sources

- [S3 client factory](../../../../../aws-java/src/main/kotlin/io/bluetape4k/aws/s3/S3ClientFactory.kt)
- [Async HTTP client providers](../../../../../aws-java/src/main/kotlin/io/bluetape4k/aws/http/SdkAsyncHttpClientProvider.kt)
- [Client configuration tests](../../../../../aws-java/src/test/kotlin/io/bluetape4k/aws/client/ClientConfigurationSupportTest.kt)
