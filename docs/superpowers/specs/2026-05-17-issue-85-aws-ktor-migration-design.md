# aws-ktor Java SDK v2 → AWS Kotlin SDK 이관 설계

**Issue**: #85
**Date**: 2026-05-17
**Author**: bluetape4k

---

## 1. 목적

`aws-ktor` 모듈이 Java SDK v2(`software.amazon.awssdk.*`) 타입을 공개 API에 노출하는 위치를 감사하고,
각 통합을 다음 세 가지로 분류한다:

- **A — 지금 이관**: AWS Kotlin SDK로 대체 가능, 명확한 마이그레이션 경로 존재
- **B — Java SDK v2 유지**: 대체 가능한 공개 API 없거나, 안정성/파리티 미달
- **C — 연기 (0.2.0)**: 이관 가능하나 파괴적 변경 또는 범위 초과

---

## 2. 현행 상태 감사 결과

### 2.1 Java SDK v2 공개 노출 위치

| 파일 | 노출 타입 | 공개 위치 | 복잡도 |
|------|-----------|-----------|--------|
| `AwsSigV4PluginConfig.kt` | `AwsCredentialsProvider`, `DefaultCredentialsProvider`, `AwsV4HttpSigner` | 공개 프로퍼티 | 높음 |
| `AwsSigV4Plugin.kt` | `AwsCredentialsProvider`, `AwsV4HttpSigner` | 플러그인 내부 사용 | 높음 |
| `S3KtorClient.kt` | `AwsCredentialsProvider`, `DefaultCredentialsProvider`, `AwsV4HttpSigner` | 생성자 파라미터, 팩토리 함수 | 높음 |
| `SqsConsumerPluginConfig.kt` | `SqsAsyncClient` | 공개 프로퍼티 | 중간 |
| `SqsConsumerRuntime.kt` | `SqsAsyncClient`, `Message` (`SqsMessageContext.message`: L175), `SendMessageResponse` (`SqsMessageContext.send()` 반환: L196) | 공개 런타임 설정 | 중간 |
| `SqsMessageConverter.kt` | `Message` | 인터페이스 파라미터, 공개 구현체 | 중간 |

### 2.2 AWS Kotlin SDK 이미 사용 중 (이관 불필요)

| 파일 | 사용 타입 |
|------|-----------|
| `DynamoDbKtorPluginConfig.kt` | `DynamoDbClient`, `CredentialsProvider`, `HttpClientEngine`, `Url` |
| `DynamoDbKtorRuntime.kt` | `DynamoDbClient` |
| `DynamoDbKtorRepository.kt` | `DynamoDbClient`, Request/Response model types |

---

## 3. 분류 및 결정

### 3.1 AwsSigV4Plugin + AwsSigV4PluginConfig → **분류 B: Java SDK v2 유지**

**현황**: Java SDK v2 `AwsV4HttpSigner`를 직접 사용해 Ktor `HttpRequest`를 서명.

**이관 가능성 평가**:

AWS Kotlin SDK(Smithy Kotlin)는 `aws-signing-default` 아티팩트에서 `AwsSigner`와
`DefaultAwsSigner`를 공개 타입으로 노출한다:

```kotlin
// aws.smithy.kotlin.runtime.auth.awssigning.AwsSigner (공개)
suspend fun sign(request: HttpRequest, config: AwsSigningConfig): AwsSigningResult<HttpRequest>
```

그러나 **drop-in 대체는 불가능**하다:
- Smithy의 `HttpRequest`는 Ktor `HttpRequestBuilder`와 타입이 다름 — 헤더/바디 어댑터 계층 별도 구현 필요
- Ktor `Send` 파이프라인 훅과 Smithy 서명 파이프라인 사이에 공식 어댑터 없음
- Ktor ↔ Smithy `HttpRequest` 변환 계층 구현 및 API 호환성 검증 비용이 큼

**결론**: Java SDK v2 `http-auth-aws` (`AwsV4HttpSigner`)는 Ktor 플러그인에서 외부 AWS 서명에 바로
사용할 수 있는 유일한 안정적 옵션이다. Smithy Kotlin SDK를 사용하려면 어댑터를 직접 구현해야 하므로
현재 시점에서는 Java SDK v2를 유지한다.

**후속**: AWS Kotlin SDK가 외부 HTTP 클라이언트용 공개 서명 API를 제공하면 이관 검토 (#85 0.2.0 리레이블).

---

### 3.2 S3KtorClient → **분류 B: Java SDK v2 유지**

**현황**: `AwsCredentialsProvider`, `AwsV4HttpSigner`를 생성자와 팩토리에 노출.

**이관 가능성 평가**:

S3KtorClient는 AwsSigV4Plugin에 직접 의존한다. SigV4 레이어가 Java SDK v2에 묶여 있으므로
S3 클라이언트도 연동하여 Java SDK v2를 유지해야 한다.

대안: AWS Kotlin SDK `S3Client`를 직접 사용하는 별도 `S3KotlinKtorClient` 추가 가능.
그러나 범위가 #11 DynamoDB Ktor 작업과 겹치므로 별도 이슈로 추적한다.

**결론**: 기존 S3KtorClient는 Java SDK v2 유지. Kotlin-first S3 Ktor 통합은 신규 이슈로 추적.

---

### 3.3 SQS Consumer → **분류 C: 0.2.0으로 연기**

**현황**: `SqsAsyncClient`(Java SDK v2 비동기 클라이언트), `Message`, `SendMessageResponse`를 공개 API에 노출.

**이관 가능성 평가**:

AWS Kotlin SDK의 `SqsClient`는 native `suspend` 함수를 제공하며 `SqsAsyncClient`보다
코루틴 친화적이다. 이관 시 얻는 이점:
- `CompletableFuture.await()` 제거
- 더 간결한 오류 처리
- `aws-kotlin` 모듈 패턴과 일관성

파괴적 변경 범위:
- `SqsConsumerPluginConfig.sqsClient` 타입: `SqsAsyncClient` → `aws.sdk.kotlin.services.sqs.SqsClient`
- `SqsMessageContext.message` 타입: `software.amazon.awssdk.services.sqs.model.Message` → `aws.sdk.kotlin.services.sqs.model.Message`
- `SqsMessageConverter` 인터페이스: `Message` 파라미터 타입 변경
- 소비자 측은 모두 재컴파일 필요

**공개 노출 상세**:
- `SqsConsumerPluginConfig.sqsClient`: `SqsAsyncClient` (공개 프로퍼티)
- `SqsMessageContext.message`: `software.amazon.awssdk.services.sqs.model.Message` (핸들러가 직접 접근 가능, `SqsConsumerRuntime.kt:175`)
- `SqsMessageContext.send()`: 반환 타입 `software.amazon.awssdk.services.sqs.model.SendMessageResponse` (`SqsConsumerRuntime.kt:196`)
- `SqsMessageConverter` 인터페이스: `Message` 파라미터 타입

**연기 사유 (릴리스 제약 명시)**:
- `0.1.0-SNAPSHOT` 릴리스가 임박하며, SQS 이관은 위 4곳의 파괴적 API 변경을 수반한다.
- SQS 이관을 0.1.0 내에 포함하면 API 안정성 보장 없이 출시하게 되므로, 마이그레이션 가이드와
  함께 0.2.0에서 이관하는 것이 타당하다.
- 이관 전 소비자는 Java SDK v2 `SqsAsyncClient`와 `Message` 타입을 직접 참조하므로
  마이그레이션 가이드(`MIGRATION.md`) 제공이 선행 조건이다.

**결론**: 0.1.0 릴리스 임박으로 인해 SQS 이관을 연기한다. 0.2.0에서 마이그레이션 가이드와 함께 이관한다.

---

### 3.4 DynamoDB → **이관 불필요 (이미 완료)**

`aws-ktor` DynamoDB 통합은 설계 시점부터 AWS Kotlin SDK를 사용한다.
Java SDK v2 타입 노출 없음. 추가 작업 불필요.

---

## 4. 공개 API 호환성 영향

| 통합 | 현재 결정 | API 파괴 여부 | 비고 |
|------|-----------|--------------|------|
| AwsSigV4Plugin | B — 유지 | 없음 | 변경 없음 |
| S3KtorClient | B — 유지 | 없음 | 변경 없음 |
| SQS Consumer | C — 0.2.0 | **있음** | `SqsAsyncClient` → `SqsClient` 타입 변경 |
| DynamoDB | 이미 완료 | 없음 | 변경 없음 |

---

## 5. 신규 통합 방침

#85 이후 `aws-ktor`에 추가되는 신규 통합은 AWS Kotlin SDK를 기본으로 한다:
- 서비스 클라이언트: `aws.sdk.kotlin.services.*`
- 자격증명: `aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider`
- HTTP 엔진: `aws.smithy.kotlin.runtime.http.engine.HttpClientEngine`
- URL: `aws.smithy.kotlin.runtime.net.url.Url`

Java SDK v2는 SigV4 서명이 명시적으로 필요한 경우에만 예외적으로 허용한다.

---

## 6. 후속 이슈 목록

| 이슈 | 내용 | 마일스톤 |
|------|------|---------|
| #85 (본 이슈, CI validation deferred) | SQS 이관 구현 | 0.2.0 |
| 신규 이슈 필요 | Kotlin-first S3 Ktor 통합 (#9 후속) | 0.2.0 |
| 신규 이슈 필요 | Smithy 공개 서명 API 추적 (SigV4 이관 선행 조건) | TBD |

---

## 7. 결론

0.1.0 출시 기준으로 `aws-ktor`의 Java SDK v2 공개 API는 **세 곳** 존재한다:
SigV4Plugin, S3KtorClient, SQS Consumer. 이 중 SigV4/S3는 AWS Kotlin SDK에
동등한 공개 서명 API가 없어 유지하고, SQS는 0.2.0 마이그레이션으로 연기한다.

DynamoDB는 이미 Kotlin-first. 신규 통합은 Kotlin SDK 기본.
