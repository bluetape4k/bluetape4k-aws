---
title: Runtime 수명 주기
description: AWS client, poller, handler, registry를 안전하게 시작하고 종료합니다.
manualId: bluetape4k-aws-ktor
chapterId: runtime-lifecycle
---

# Runtime 수명 주기

Ktor runtime은 client, coroutine job, database registry와 실행 중인 handler를 소유할 수 있습니다. 만들 때 소유 여부를 기록해야 종료 시 runtime이 직접 만든 자원만 닫을 수 있습니다.

## 시작

job을 띄우기 전에 설정을 검증하세요. queue name 해석, database registry 생성, 서비스 client 생성은 제한된 시작 timeout 안에서 끝내야 합니다. 시작 도중 실패했다면 이미 만든 자원도 모두 정리해야 합니다.

## 구조화된 백그라운드 작업

poller와 handler는 `GlobalScope`가 아니라 애플리케이션 수명 주기 아래에서 실행합니다. 동시성을 제한하고 blocking 경계에는 전용 또는 제한된 I/O dispatcher를 사용하세요. child 하나의 실패가 관계없는 서비스 작업까지 조용히 죽이지 않도록 runtime의 supervision 정책을 분명히 해야 합니다.

## 종료 순서

1. 새 receive와 서비스 작업을 예약하지 않습니다.
2. poller에 종료를 알리거나 취소합니다.
3. `shutdownTimeout`까지 실행 중인 handler를 기다립니다.
4. 중단된 메시지에 설정된 실패·visibility 결과를 적용합니다.
5. plugin이 소유한 client와 registry를 닫습니다.
6. 주입받은 자원은 소유자가 닫도록 남겨 둡니다.

```kotlin
environment.monitor.subscribe(ApplicationStopping) {
    // 애플리케이션 소유 공유 client보다 plugin 작업이 먼저 멈춘다.
}
```

## 종료 중 SQS visibility

handler가 visibility보다 오래 실행될 수 있다면 heartbeat 연장이나 더 긴 timeout을 사용하세요. 강제 종료 시 즉시 redelivery가 만료를 기다리는 것보다 나을 수 있지만 handler가 멱등할 때만 안전합니다.

## 관측성

poll, receive, convert, invoke, acknowledge, failure 단계를 제한된 tag로 기록하세요. active job 수와 종료 시간도 관측합니다. queue URL, message body, object key를 제한 없는 metric tag로 사용하면 안 됩니다.

## 검증

수명 주기 테스트는 애플리케이션을 반복해서 시작·종료하고 남은 job이 없는지 확인해야 합니다. 주입한 client는 열린 채로 남고 plugin이 만든 client는 닫히는지, timeout 취소가 동작하는지도 검증하세요.

## 근거 자료

- [SQS runtime](../../../../../aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerRuntime.kt)
- [Exposed runtime](../../../../../aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/exposed/AwsExposedKtorRuntime.kt)
- [CloudWatch runtime](../../../../../aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/cloudwatch/CloudWatchKtorRuntime.kt)

