---
title: 동기·비동기·coroutine API
description: blocking, CompletableFuture, suspend AWS 호출을 의도적으로 선택하는 기준입니다.
manualId: bluetape4k-aws-java
chapterId: sync-async-coroutines
---

# 동기·비동기·coroutine API

Java SDK v2 확장 계층은 세 가지 호출 방식을 일부러 구분해 둡니다. 모든 호출을 무조건 coroutine으로 감싸기보다 애플리케이션 경계에서 알맞은 방식을 선택하세요.

## 세 단계 API

- 동기 extension은 동기 AWS client를 호출하며 응답이 올 때까지 스레드를 막습니다.
- async extension은 `CompletableFuture`를 반환해 Java 비동기 코드와 조합하기 좋습니다.
- coroutine extension은 async client를 호출하고 future가 끝날 때까지 suspend합니다.

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

## 취소는 협력적이다

coroutine을 취소하면 future를 기다리는 작업은 멈추지만 AWS의 원격 작업은 이미 진행 중일 수 있습니다. 여러 요청으로 이루어진 작업이 원자적으로 취소된다고 가정하지 마세요. 쓰기 작업은 idempotency key나 안전한 재시도 전략을 갖추고 완료·실패를 따로 관측해야 합니다.

## Flow와 pagination

`listAllObjects` 같은 pagination helper는 cold `Flow`를 반환합니다. collect하기 전에는 요청하지 않고, 수집할 때 continuation token을 따라 페이지를 순서대로 읽습니다. 서비스 전체 목록을 메모리에 한꺼번에 담기보다 후속 작업의 동시성을 명시적으로 제한하세요.

## 오류 의미

AWS SDK service exception을 그대로 유지하고, 재시도 가능 여부와 업무 의미를 아는 애플리케이션 경계에서 변환하세요. 로그에는 request ID를 남기되 credentials, KMS 평문, secret payload는 기록하면 안 됩니다.

## 선택 기준

전용 blocking 경계에서는 sync API, Java 조합에는 future, suspend 중심 Kotlin 서비스에는 coroutine extension을 사용합니다. 동기 SDK 호출을 `Dispatchers.Default`에서 실행하지 말고 async client나 명시적인 I/O 경계를 선택하세요.

## 근거 자료

- [S3 coroutine extension](../../../../../aws-java/src/main/kotlin/io/bluetape4k/aws/s3/S3AsyncClientCoroutinesExtensions.kt)
- [SQS async extension](../../../../../aws-java/src/main/kotlin/io/bluetape4k/aws/sqs/SqsAsyncClientExtensions.kt)
- [Coroutine bridge](../../../../../aws-java/src/main/kotlin/io/bluetape4k/aws/coroutines/AwsCoroutineSupport.kt)

