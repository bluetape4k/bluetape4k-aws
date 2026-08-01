# aws-ktor Migration Audit: Java SDK v2 노출 분류

**날짜**: 2026-05-17
**이슈**: #85
**브랜치**: docs/aws-ktor-migration-design

## 요약

공개 API에서 Java SDK v2 (`software.amazon.awssdk.*`) type을 노출하는 모든
`aws-ktor` 지점을 audit하고 AWS Kotlin SDK migration 관점에서 각 통합을 분류했다.

## 주요 발견

### Java SDK v2 노출(공개 API surface 3개)

1. **AwsSigV4Plugin 생태계**: 공개 config의 `AwsCredentialsProvider`, `AwsV4HttpSigner`
2. **S3KtorClient**: Constructor와 factory의 같은 credentials/signer type
3. **SQS Consumer**: 공개 API의 `SqsAsyncClient`, `Message`, `SendMessageResponse`

### 이미 AWS Kotlin SDK 사용(DynamoDB)

DynamoDB Ktor 통합은 처음부터 Kotlin 우선으로 설계했다. Migration이 필요하지 않다.

## 결정

| 통합 | 결정 | 이유 |
|---|---|---|
| AwsSigV4Plugin | Java SDK v2 유지 | Smithy Kotlin에 외부 HTTP client용 공개 signing API가 없음 |
| S3KtorClient | Java SDK v2 유지 | SigV4 결정에 종속됨 |
| SQS Consumer | 0.2.0 버전으로 연기 | 호환성을 깨는 API 변경이며 0.1.0 버전에서는 긴급하지 않음 |
| DynamoDB | 이미 migration됨 | 조치 불필요 |

## 교훈: AWS Kotlin SDK Signing API — 바로 대체할 수 없지만 없는 것은 아님

AWS Kotlin SDK (`aws.smithy.kotlin:aws-signing-default`)는 공개 type으로 `AwsSigner`와
`DefaultAwsSigner`를 **제공한다**.

```kotlin
suspend fun sign(request: HttpRequest, config: AwsSigningConfig): AwsSigningResult<HttpRequest>
```

하지만 Smithy의 `HttpRequest`는 Ktor의 `HttpRequestBuilder`와 다른 type이다. Ktor
plugin에서 Smithy signing을 사용하려면 Ktor ↔ Smithy `HttpRequest`/body/header adapter
layer가 필요하다. 공식 bridge가 없고 이를 직접 만들면 통합 비용이 상당하다.

**교훈**: Smithy signing에 "공개 API가 없다"고 말하지 않는다. 정확한 표현은 "현재 Ktor
plugin에 바로 적용할 수 없으며 Ktor ↔ Smithy `HttpRequest` adapter와 API compatibility
작업이 필요하다"이다.

현재 Java SDK v2의 `http-auth-aws` module (`AwsV4HttpSigner`)이 Ktor 기반 SigV4
plugin에서 바로 사용할 수 있는 유일하게 안정적인 선택지다.

SigV4 migration을 계획하기 전에 upstream Smithy Kotlin project에 공식 Ktor adapter
또는 공개 signing bridge가 추가되는지 추적한다.

## 정책: 새 통합

#85 이후 추가하는 모든 `aws-ktor` 통합은 기본적으로 AWS Kotlin SDK를 사용한다. AWS
Kotlin SDK가 안정적인 parity를 제공하지 않는 경우(SigV4)에만 Java SDK v2를 허용한다.
