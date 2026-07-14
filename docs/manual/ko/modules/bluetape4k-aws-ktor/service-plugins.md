---
title: 서비스 plugin
description: 명시적인 client·runtime 소유권으로 typed Ktor AWS plugin을 설정합니다.
manualId: bluetape4k-aws-ktor
chapterId: service-plugins
---

# 서비스 plugin

Ktor 서비스 plugin은 애플리케이션 설정을 typed runtime으로 바꾸고 application attribute나 operation interface로 노출합니다.

## SQS consumer

`queueUrl`과 `queueName` 중 정확히 하나를 지정해야 하며 plugin instance 하나에는 handler 하나만 등록할 수 있습니다. poller 수, receive 크기, long poll, visibility, 삭제, 실패 visibility, heartbeat, 변환 정책, 종료 timeout을 하나의 전달 계약으로 설정하세요.

```kotlin
install(SqsConsumer) {
    queueName = "orders"
    coroutines = 4
    maxMessages = 10
    waitTimeSeconds = 20
    onMessage<OrderMessage> { order -> process(order) }
}
```

주입한 `SqsAsyncClient`는 plugin이 닫지 않습니다. plugin이 직접 만든 client만 runtime이 소유하고 닫습니다.

## DynamoDB

DynamoDB plugin은 async·enhanced client를 만들거나 주입받고 repository runtime을 제공합니다. table 생성, index 선택, capacity, 재시도, 멱등성은 여전히 애플리케이션 책임입니다.

## Exposed

미리 만든 `databaseProperties`와 Ktor DSL 중 하나만 사용하세요. 둘을 섞으면 거부됩니다. 시작·종료 timeout, blocking transaction context, settings resolver를 설정합니다. runtime은 자신이 만든 registry를 닫습니다.

## 그 밖의 plugin

CloudWatch, Logs, IMDS, Access Grants, S3 Vectors도 같은 패턴을 따릅니다. 선택적 서비스 SDK, typed configuration, 명시적 소유권, runtime operations, stop hook으로 구성됩니다.

## 테스트

`testApplication`과 주입한 fake client로 수명 주기를 검증하고 서비스 동작은 Floci에서 테스트하세요. 누락·중복 설정, 취소, 변환 실패, redelivery, 종료 정리를 포함해야 합니다.

## 근거 자료

- [SQS consumer 설정](../../../../../aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerPluginConfig.kt)
- [DynamoDB plugin](../../../../../aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/dynamodb/DynamoDbKtorPlugin.kt)
- [Exposed plugin 설정](../../../../../aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/exposed/AwsExposedPluginConfig.kt)

