# CLAUDE.md — bluetape4k-aws

AWS SDK v2 + AWS Kotlin SDK 래퍼. Coroutines, Spring Boot 4, Ktor 3 지원.

- **Group**: `io.github.bluetape4k.aws` · **Base version**: `0.1.0-SNAPSHOT`
- **Publishing**: Maven Central via `nmcp` (`publishingType=AUTOMATIC`)

## Repository Layout

| Module | Status | Description |
|---|---|---|
| `aws/` | stable | AWS Java SDK v2 — sync/async(`CompletableFuture`)/Coroutines extensions (DynamoDB, S3, SES, SNS, SQS, KMS, CloudWatch, Kinesis, STS) |
| `aws-kotlin/` | stable | AWS Kotlin SDK — native `suspend` functions + DSL builders |
| `aws-spring-boot/` | WIP | Spring Boot 4 auto-configuration (no awspring dep — pure Coroutines) |
| `aws-ktor/` | WIP | Ktor 3 client/server integration |

> 통합 테스트는 LocalStack via Testcontainers. `-Dbluetape4k.aws.emulator=localstack|floci` (default: `localstack`)

## Build Commands

```bash
./gradlew build -x test --parallel
./gradlew :aws:test
./gradlew :aws-kotlin:test
./gradlew :aws:test --tests "io.bluetape4k.aws.s3.S3ClientSupportTest"
./gradlew :aws:test -Dbluetape4k.aws.emulator=floci
./gradlew build
./gradlew detekt
./gradlew publishBluetapeAwsPublicationToCentralPortal           # SNAPSHOT
./gradlew publishBluetapeAwsPublicationToCentralPortal -PsnapshotVersion=   # RELEASE
```

## AWS 특이사항

### SDK 의존성 선언 방식

`aws` + `aws-kotlin` 모두 AWS 서비스 SDK를 `compileOnly` 로 선언.
소비자가 실제로 사용하는 서비스 런타임 의존성을 직접 추가해야 함.

### Coroutines 패턴

- `aws` 모듈: `CompletableFuture` → `.await()` 로 래핑
- `aws-kotlin` 모듈: AWS Kotlin SDK의 native `suspend` 함수 직접 사용
- 블로킹 AWS 호출 → `withContext(Dispatchers.IO)` 래핑

### Client 라이프사이클 (aws-kotlin)

AWS Kotlin SDK 클라이언트는 연결 풀 + 스레드 보유. 반드시 닫아야 함:
- 단기: `withXxxClient { }` (취소 시에도 자동 close)
- 장기: 애플리케이션 종료 시 `close()` 명시적 호출
