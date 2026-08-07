---
manualId: "spring-vs-ktor"
title: "AWS 연동에서 Spring Boot와 Ktor 선택"
locale: "ko"
releaseRef: "0.5.0"
---

# AWS 연동에서 Spring Boot와 Ktor 선택

애플리케이션 설정과 lifecycle을 이미 관리하는 framework에 AWS 연동을 맞춘다. 두 모듈 모두 coroutine 친화적인 API를 제공한다. 차이는 client, background 작업, property와 종료 과정을 조립하는 방식에 있다.

## 선택 기준

| 요구사항 | Spring Boot | Ktor |
| --- | --- | --- |
| 설정 | `bluetape4k.aws.*` property와 조건부 bean | 설치한 plugin과 공유 `AwsKtorDefaults` |
| Resource 소유권 | Spring bean과 destroy method | Plugin runtime, 그리고 주입/직접 생성 구분 |
| S3 | Template, Transfer Manager, presigning, 설정 source, encryption | SigV4 Ktor client, object/multipart/presigning, encryption |
| DynamoDB | Coroutine repository와 선택적 DAX | Kotlin SDK repository plugin과 명시적 table 정의 |
| Messaging | `@SqsListener`, container registry, SNS template와 HTTP parsing | Handler, interceptor, observer, ack/nack를 갖춘 SQS consumer plugin |
| Database | AWS 설정 기반 Exposed registry 자동 설정 | `AwsExposedPlugin`과 route-level database 접근 |
| 관측성 | Spring 관례에 맞춘 Micrometer 연동 | Plugin에 연결하는 선택적 Micrometer observer/template |

## Infrastructure를 context가 관리한다면 Spring Boot

Configuration properties, conditional auto-configuration, bean 교체, actuator와 context 종료를 이미 쓰고 있다면 Spring Boot가 잘 맞는다. 이 모듈은 awspring에 의존하지 않는다. Service SDK class와 설정이 있을 때만 해당 client를 만들기 때문에, 사용할 AWS service artifact는 애플리케이션이 직접 추가해야 한다.

Spring 예제에서는 property override, application bean, controller 또는 listener, Testcontainers emulator와 예제에 포함된 AOT task를 한 흐름으로 볼 수 있다.

## Plugin이 runtime 경계를 맡는다면 Ktor

Ktor는 plugin 설치와 작은 runtime 조합을 명시적으로 구성하려는 서비스에 맞는다. 각 plugin은 자체 설정을 검증하고 client를 직접 만들었는지 주입받았는지 기록한다. SQS polling이나 CloudWatch Logs buffering 같은 background runtime은 Ktor application lifecycle에 연결되며, 정해진 시간 안에서 종료한다.

Plugin을 쓴다고 모든 resource가 plugin 소유가 되는 것은 아니다. 주입한 client는 계속 애플리케이션 소유다. Client 생성 위치를 composition root 한 곳에 모아 두면 종료 규칙을 놓치기 어렵다.

## Framework를 서로 감싸지 않는다

Spring Boot 애플리케이션에서 helper 하나를 쓰려고 Ktor plugin lifecycle까지 들여오면 종료 모델이 두 개가 된다. Framework adapter가 맞지 않을 때는 기반 모듈인 `bluetape4k-aws-java`, `bluetape4k-aws-kotlin` 또는 `bluetape4k-aws-exposed`를 직접 사용한다. Ktor 애플리케이션에 Spring 자동 설정을 넣는 경우도 같은 기준을 적용한다.

## 근거 소스

- [Spring AWS 자동 설정](../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/AwsAutoConfiguration.kt)
- [Spring AWS property](../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/AwsProperties.kt)
- [Ktor 공유 기본 설정](../../../../aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/AwsKtorCore.kt)
- [Ktor SQS plugin](../../../../aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerPlugin.kt)
- [Ktor Exposed plugin](../../../../aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/exposed/AwsExposedPlugin.kt)

