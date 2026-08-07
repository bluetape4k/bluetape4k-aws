---
manualId: "runtime-boundaries"
title: "Runtime과 Client 소유권"
locale: "ko"
releaseRef: "0.5.0"
---

# Runtime과 Client 소유권

AWS 클라이언트는 API 객체 하나로 끝나지 않는다. HTTP 엔진과 연결 풀, 자격 증명 공급자, 작업 코루틴, 백그라운드 폴러를 함께 품을 수 있다. 종료 책임은 자원을 만든 쪽에 둬야 한다.

## 소유권 표

| 생성 경로 | 소유자 | 종료 원칙 |
| --- | --- | --- |
| 애플리케이션이 Java SDK 클라이언트 생성 | 애플리케이션 | 애플리케이션 종료 시 클라이언트를 닫는다 |
| 별도 엔진 없이 Kotlin SDK 클라이언트 생성 | 애플리케이션 | 클라이언트를 닫으면 SDK가 관리하던 엔진도 닫힌다 |
| 공유 Kotlin SDK HTTP 엔진 주입 | 애플리케이션 | 모든 클라이언트를 닫은 뒤 공유 엔진을 한 번 닫는다 |
| Spring 자동 설정이 클라이언트 또는 레지스트리 생성 | Spring 컨텍스트 | 빈의 종료 메서드에 맡기고 같은 객체를 직접 또 닫지 않는다 |
| Ktor 플러그인이 클라이언트 생성 | Ktor 플러그인 런타임 | 애플리케이션 종료 시 작업을 멈추고 소유한 클라이언트를 닫는다 |
| Ktor 플러그인에 클라이언트 주입 | 애플리케이션 | 플러그인은 작업만 멈추며 주입받은 클라이언트는 닫지 않는다 |
| `S3KtorClient` 팩토리가 HTTP 클라이언트와 공급자 생성 | `S3KtorClient` | `close()` 또는 `use`로 래퍼와 소유 자원을 함께 닫는다 |
| Exposed 팩토리가 데이터베이스 레지스트리 생성 | 레지스트리를 만든 쪽 | 레지스트리로 이름 있는 핸들과 기본 핸들을 정리한다 |

## Kotlin SDK 공유 엔진

`HttpClientEngineProvider`는 CRT와 OkHttp 공유 엔진 싱글턴을 제공한다. 이 엔진을 Kotlin SDK 클라이언트에 명시적으로 넘기면 클라이언트가 관리하지 않는다. 여러 클라이언트가 전송 자원을 함께 쓸 때 유용하지만, 엔진 종료 책임은 애플리케이션으로 넘어온다. 특히 CRT 엔진은 비데몬 스레드를 사용하므로 클라이언트만 닫고 공유 엔진을 남겨 두면 JVM이 끝나지 않을 수 있다.

클라이언트가 하나라면 SDK가 엔진을 관리하게 두는 편이 단순하다. 공유 엔진은 종료 담당자가 분명하고, 모든 클라이언트가 멈춘 뒤 한 번 닫을 수 있을 때 사용한다.

## 프레임워크가 만든 클라이언트와 주입받은 클라이언트

Ktor DynamoDB와 SQS 플러그인은 클라이언트를 직접 만들었는지 외부에서 받았는지 기록한다. 직접 만든 클라이언트는 런타임이 끝날 때 닫고, 주입받은 클라이언트는 건드리지 않는다. 클라이언트를 재사용하면서 중복 종료를 피하려면 구성 진입점에서 소유권을 분명히 드러내야 한다.

Spring 자동 설정은 닫을 수 있는 클라이언트와 레지스트리에 종료 메서드를 지정한다. 애플리케이션이 자동 설정 빈을 교체한다면 새 빈 정의에서도 같은 수명 주기 계약을 지켜야 한다.

## Transport보다 작업을 먼저 멈춘다

오래 실행되는 runtime은 다음 순서로 끝낸다.

1. 새 polling이나 요청 수락을 중단한다.
2. 정해 둔 시간 안에서 진행 중인 작업을 마치거나 취소한다.
3. 버퍼가 있다면 문서에 정한 범위 안에서 비운다.
4. 클라이언트, 풀, 공유 엔진을 의존성 역순으로 닫는다.

Ktor SQS 런타임은 폴러를 멈추고 `shutdownTimeout`까지 핸들러를 기다린 다음, 남은 작업을 취소하고 소유한 클라이언트를 닫는다. CloudWatch Logs에는 버퍼를 비우는 별도의 종료 제한 시간이 있다. 이 값들은 내부 구현에만 머무르지 않는다. 프로세스 종료 예산에 맞춰 운영 설정으로 관리해야 한다.

## 근거 소스

- [Kotlin SDK 공유 엔진 소유권](../../../../aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/http/HttpClientEngineProvider.kt)
- [Ktor SQS 플러그인 소유권 계약](../../../../aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerPluginConfig.kt)
- [Ktor SQS 종료 순서](../../../../aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerRuntime.kt)
- [Ktor S3 클라이언트 소유권](../../../../aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/s3/S3KtorClient.kt)
- [Exposed 데이터베이스 레지스트리](../../../../aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsExposedDatabaseRegistry.kt)
