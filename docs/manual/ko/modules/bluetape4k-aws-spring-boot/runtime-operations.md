---
title: Runtime 운영
description: Spring AWS 통합을 제한된 수명 주기와 관측성으로 운영합니다.
manualId: bluetape4k-aws-spring-boot
chapterId: runtime-operations
---

# Runtime 운영

운영 안정성은 자동 설정 자체보다 수명 주기, 제한된 동시성, 관측성, 비밀 값 관리에서 나옵니다.

## Client 소유권

자동 설정이 만든 client는 Spring이 소유합니다. 애플리케이션이 제공한 client는 closeable bean으로 등록하지 않는 한 애플리케이션 소유입니다. listener container가 client보다 먼저 멈춰야 하며 요청 handler에서 공유 client를 닫으면 안 됩니다.

## SQS runtime

consumer 수, long-poll 시간, 한 번에 받을 메시지 수, visibility timeout, 실패 visibility, 종료 timeout을 함께 조정하세요. poller를 늘리면 처리량뿐 아니라 실행 중인 메시지와 downstream 압력도 커집니다. 끝없는 사용자 정의 재시도보다 SQS native redrive policy를 우선하세요.

## 관측성

`MeterRegistry`가 있으면 S3·SQS 작업과 listener 단계에 낮은 cardinality timer를 붙일 수 있습니다. bucket key, message body, secret ID, 제한 없는 예외 문자열을 metric tag로 사용하지 마세요. 로그에는 AWS request ID를 남겨 상관관계를 추적합니다.

## 원격 설정

Secrets Manager, Parameter Store, S3 config loader는 환경 준비 단계에서 실행됩니다. 필수 source 조회가 실패하면 시작을 실패시키세요. 요청마다 AWS를 호출하지 말고 해석한 설정을 environment에 보관합니다.

## Graceful shutdown

새 요청을 막고 listener polling을 멈춘 다음 설정한 timeout까지 handler를 기다립니다. 그 뒤 소유한 서비스 client와 database pool 순서로 닫으세요. 같은 순서를 테스트에서도 검증해야 합니다.

## 운영 점검표

- 배포 환경과 region·endpoint가 맞는가
- credentials가 최소 권한이며 교체되는가
- 활성화한 통합에 필요한 서비스 SDK JAR이 있는가
- 재시도 예산이 제한되어 있는가
- metric·로그에 비밀 값과 높은 cardinality 식별자가 없는가
- 로컬 기본 emulator는 Floci이고 LocalStack은 명시적 fallback인가

## 근거 자료

- [SQS listener container](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsMessageListenerContainer.kt)
- [Micrometer SQS interceptor](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/MicrometerSqsListenerInterceptor.kt)
- [Secrets environment processor](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/secretsmanager/SecretsManagerEnvironmentPostProcessor.kt)

