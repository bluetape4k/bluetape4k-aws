---
manualId: "sdk-selection"
title: "AWS Java SDK와 Kotlin SDK 선택"
locale: "ko"
releaseRef: "0.5.0"
---

# AWS Java SDK와 Kotlin SDK 선택

두 경로 모두 코루틴 기반 애플리케이션에서 쓸 수 있지만 출발점이 다르다. 호출 함수에 `suspend`가 붙었는지만 보지 말고, 클라이언트 모델과 필요한 AWS 기능, 주변 라이브러리를 함께 보고 고른다.

![AWS SDK 선택 지도](../../assets/sdk/sdk-decision-map.png)

## 선택 기준

| 질문 | Java SDK v2 경로 | Kotlin SDK 경로 |
| --- | --- | --- |
| 기존 클라이언트 | Java SDK v2 클라이언트를 이미 공유한다면 자연스럽다 | 새 서비스를 Kotlin SDK 중심으로 만들 때 자연스럽다 |
| 비동기 모델 | `CompletableFuture`와 `suspend` 어댑터 | 네이티브 `suspend` 작업 |
| DynamoDB | 표준/Enhanced 클라이언트, 테이블 스키마, 코루틴 저장소 | 네이티브 클라이언트, 요청 DSL, 일괄 실행기 |
| S3 전송 | Java Transfer Manager와 비동기 클라이언트 확장 | Kotlin 클라이언트 객체 도우미 |
| 이 릴리스의 프레임워크 연동 | Spring Boot 연동이 주로 Java SDK 클라이언트를 만든다 | Ktor DynamoDB는 Kotlin SDK를 쓰며, 다른 Ktor 기능은 Java SDK나 Ktor HTTP를 쓸 수 있다 |
| HTTP 전송 계층 | AWS Java SDK HTTP 클라이언트 구현 | Smithy Kotlin CRT/OkHttp 엔진 |
| 종료 시 주의점 | 동기/비동기 클라이언트와 소유한 전송 계층을 닫는다 | 클라이언트를 닫고, 명시적으로 공유한 엔진은 따로 닫는다 |

## 기존 생태계와 이어야 하면 Java SDK v2

애플리케이션에 Java SDK 클라이언트가 이미 있거나 Enhanced DynamoDB API, S3 Transfer Manager, Spring Boot 자동 설정을 사용한다면 `bluetape4k-aws-java`가 잘 맞는다. 지원하는 서비스에는 직접 호출하는 동기 도우미, 비동기 `CompletableFuture` 확장과 비동기 클라이언트를 감싼 `suspend` 확장이 마련되어 있다.

`suspend` 확장을 쓴다고 동기 클라이언트의 I/O가 논블로킹으로 바뀌지는 않는다. 코루틴 취소와 논블로킹 전송 계층이 설계 조건이라면 비동기 클라이언트 확장을 사용해야 한다.

## Suspend 모델이 중심이면 Kotlin SDK

AWS SDK for Kotlin을 주 클라이언트 계층으로 삼는다면 `bluetape4k-aws-kotlin`을 사용한다. 서비스 클라이언트가 이미 `suspend` 작업을 제공하고, bluetape4k가 요청 빌더, 매핑 도우미, 일괄 처리와 Kinesis 레코드 흐름 같은 상위 기능을 보탠다.

Kotlin SDK도 자원을 소유한다. 클라이언트가 직접 관리하는 HTTP 엔진은 클라이언트와 함께 닫힌다. 반면 외부에서 넘긴 공유 엔진은 닫지 않으므로 모든 클라이언트가 멈춘 뒤 애플리케이션이 정리해야 한다.

## 두 SDK를 함께 쓸 때

Ktor 서비스에서 Kotlin SDK DynamoDB 플러그인과 Java SDK SQS 소비자를 함께 쓰는 구성도 가능하다. 이 경우에는 런타임 스택이 둘이라고 생각하는 편이 안전하다.

- 리전, 자격 증명, 엔드포인트 재정의, 재시도와 제한 시간을 각각 설정한다.
- 도메인 코드에서 두 SDK 모델을 계속 변환하지 않는다.
- 클라이언트와 엔진을 누가 닫는지 구성 요소마다 기록한다.
- 두 에뮬레이터 경로를 따로 검증한다.

한 SDK만으로 요구사항을 충족할 수 있다면 작은 문법 차이보다 단순한 소유권 모델이 운영에서 더 큰 이점이 된다.

## 근거 소스

- [Java SDK coroutine 지원](../../../../aws-java/src/main/kotlin/io/bluetape4k/aws/coroutines/AwsCoroutineSupport.kt)
- [Java SDK DynamoDB 코루틴 저장소](../../../../aws-java/src/main/kotlin/io/bluetape4k/aws/dynamodb/repository/DynamoDbCoroutineRepository.kt)
- [Kotlin SDK DynamoDB 클라이언트 확장](../../../../aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/dynamodb/DynamoDbClientExtensions.kt)
- [Kotlin SDK Kinesis flow](../../../../aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisRecordFlow.kt)
