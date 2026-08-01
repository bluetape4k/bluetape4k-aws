# Kinesis `recordFlow` 구현 — 교훈 문서

**이슈**: #81 — `aws-kotlin`용 Kinesis Coroutine `Flow<Record>`
**날짜**: 2026-05-17
**모듈**: `aws-kotlin`
**담당**: bluetape4k AI

---

## 1. 배경

AWS Kinesis 샤드에서 레코드를 지속 폴링하는 cold `Flow<Record>`를 `aws-kotlin` 모듈에 추가했다.
기존 `KinesisClientExtensions.kt`는 단순 send/receive 래퍼만 제공했으므로,
소비자가 직접 이터레이터 관리·재시도·백오프 로직을 구현해야 하는 문제가 있었다.

---

## 2. 핵심 설계 결정

### 2-1. `var currentPosition` (`val` 아님)

`ExpiredIteratorException` 복구 시 `currentPosition`을 `AfterSequenceNumber(lastSeen)`으로 교체해야 한다.
`val`로 선언하면 불변이므로 `var`로 선언했다.

### 2-2. `shardIterator = null` 센티넬 패턴

이터레이터 초기 획득과 만료 후 재획득을 모두 `try {}` 블록 안의 null 체크로 위임했다.
`catch` 블록에서 직접 `fetchShardIterator`를 호출하면 해당 예외가 sibling `catch`에 처리되지 않고
외부 컨텍스트로 전파되므로, 복구 fetch는 반드시 다음 반복의 `try {}` 안에서 수행해야 한다.

```kotlin
} catch (e: ExpiredIteratorException) {
    currentPosition = lastSeen?.let { AfterSequenceNumber(it) } ?: currentPosition
    shardIterator = null   // null → 다음 반복 try 블록에서 fetch
}
```

### 2-3. `Latest` + 체크포인트 없음 → 즉시 실패

`Latest` 포지션에서 첫 레코드를 처리하기 전에 이터레이터가 만료된 경우,
새 `Latest` 이터레이터를 재획득하면 5분 TTL 동안 작성된 모든 레코드를 무음 skip하게 된다.
따라서 이 경우 복구를 시도하지 않고 즉시 `ExpiredIteratorException`을 전파한다.

```kotlin
val lastSeen = lastSeenSequenceNumber
if (lastSeen == null && currentPosition is KinesisStartingPosition.Latest) {
    throw e   // 재시도 없이 즉시 전파
}
```

### 2-4. `e.sdkErrorMetadata.isRetryable` (`e.isRetryable` 아님)

AWS Kotlin SDK `KinesisException`에는 `isRetryable` 프로퍼티가 없다.
올바른 경로는 `e.sdkErrorMetadata.isRetryable`이다.

### 2-5. Kotlin catch-guard 문법 부재

Kotlin에는 Java `catch (KinesisException e) if (condition)` 구문이 없다.
catch 블록 첫 줄에서 `if (!e.sdkErrorMetadata.isRetryable) throw e`로 처리해야 한다.

### 2-6. 지터 백오프: Duration 오버플로 방지

Duration 산술 대신 Long(ms) 공간에서 계산한 뒤 `Duration.Companion.milliseconds`로 변환.
`Duration * Int` 연산에서 높은 `attempt` 값이 Long 내부 ns 오버플로를 유발할 수 있으므로
ms 단위로 변환 후 `shl`과 `coerceAtMost`를 적용한다.

```kotlin
val maxMs  = options.maxThrottleBackoff.inWholeMilliseconds
val baseMs = options.initialThrottleBackoff.inWholeMilliseconds
val cappedMs = (baseMs shl (attempt - 1).coerceAtMost(30)).coerceAtMost(maxMs)
return Random.Default.nextLong(0L, cappedMs + 1L).milliseconds
```

### 2-7. `AtTimestamp` → `SmithyInstant.fromEpochSeconds` 두 번째 인자는 `Int`

`java.time.Instant.nano`는 `Int`다. `toLong()` 변환 없이 직접 전달해야 한다.

---

## 3. 테스트 패턴

### 3-1. MockK `{ throw X }()` 안티패턴

`returnsMany listOf( { throw X }() )` 패턴은 람다가 리스트 생성 시점에 즉시 실행되어
테스트 셋업 단계에서 예외를 던진다. 올바른 패턴은 `answers { ... }` 블록이다:

```kotlin
var count = 0
coEvery { client.getRecords(any()) } answers {
    when (count++) {
        0    -> successResponse
        else -> throw ExpiredIteratorException { message = "expired" }
    }
}
```

### 3-2. MockK `KinesisException` mock은 `relaxed = true` 필수

`KinesisException`을 mock 할 때 `relaxed = true` 없이 생성하면
JVM이 예외 출력/로깅 중 `getCause()`를 호출해 MockK 예외가 발생한다.

### 3-3. 통합 테스트: `runSuspendIO` + `withTimeout`

LocalStack 통합 테스트에서 Flow 수집은 `runTest`가 아닌 `runSuspendIO`를 사용하고,
`withTimeout(30.seconds)`로 무한 대기를 방지한다.

```kotlin
@Test
fun `TrimHorizon collects records`() = runSuspendIO {
    withKinesisClient(...) { client ->
        val collected = withTimeout(30.seconds) {
            client.recordFlow(STREAM, shardId).take(N).toList()
        }
    }
}
```

---

## 4. Step 3-R 리뷰 수렴 이력

| 라운드 | 리뷰어 | P0 | P1 | 주요 수정 |
|---|---|---|---|---|
| 1단계 (4종) | Developer/Security/Ops/Caller | 3 | 15 | — |
| 6단계 Advisor | Claude Code | 0 | 3 | — |
| 2단계 Critic | 통합 | 0 | 0 | 계획 v2 |
| Advisor 추가 | Claude Code | 1 | 0 | recovery fetch 재시도 범위 (v2.1) |
| Codex 3단계 | Codex | 0 | 1 | Latest 즉시 실패 보호 장치 (v2.2) |
| **최종** | **전체** | **0** | **0** | **수렴 완료** |

---

## 5. 향후 작업

- **Issue #81 후속 작업**: DynamoDB Streams `Flow<Record>` 구현 (유사 패턴 적용 가능)
- **멀티샤드 팬아웃**: `describeStream` + `merge(shards.map { recordFlow(..., it) })`는 소비자 책임
- **체크포인트 저장**: DynamoDB / Redis 기반 체크포인트 유틸리티 (별도 이슈 예정)
