---
title: 서비스별 사용 패턴
description: S3, DynamoDB, messaging, KMS와 테스트에 공통 패턴을 적용합니다.
manualId: bluetape4k-aws-java
chapterId: service-patterns
---

# 서비스별 사용 패턴

이 모듈은 서비스마다 같은 구조를 반복합니다. request builder로 보일러플레이트를 줄이고, client extension으로 AWS client를 드러내며, 상위 helper에 pagination·batch·repository 규칙을 담습니다.

## S3

작은 객체 작업은 client extension을 사용합니다. 대용량 또는 multipart 전송은 Transfer Manager가 알맞습니다. 일반 move는 copy 후 delete이므로 delete가 실패하면 두 객체가 모두 남을 수 있습니다. 복사본 삭제로 되돌리는 방식이 업무 요구에 맞을 때만 atomic helper를 선택하세요.

## DynamoDB

typed entity에는 enhanced-client schema와 `DynamoDbCoroutineRepository`를 사용합니다. DynamoDB batch는 일부만 성공할 수 있으므로 unprocessed key·item을 반드시 처리해야 합니다. query DSL은 expression 실수를 줄여 주지만 index와 capacity 전략까지 대신 정하지는 않습니다.

## SQS와 SNS

long polling으로 빈 receive를 줄이세요. SQS 메시지는 처리가 성공한 뒤 삭제하고, 실패 시 재시도 의미에 맞게 visibility를 바꿉니다. SNS HTTP parsing은 서명 검증이 아닙니다. 알림을 처리하기 전에 인증서 체인, signature, version, 예상 topic을 검증하세요.

## KMS와 비밀 값

평문은 가능한 가장 좁은 범위에서만 다룹니다. reveal한 값을 data class 문자열, 구조화 로그, 예외 메시지에 넣으면 안 됩니다. throttling 실패는 애플리케이션의 제한된 재시도 정책으로 처리하고 무한 반복하지 마세요.

## 서비스 SDK 정책

`bluetape4k-aws-java`는 서비스 SDK를 `compileOnly`로 사용합니다. 소비자는 `bluetape4k-dependencies`를 가져오고 이 라이브러리는 버전 없이 선언한 뒤 필요한 `software.amazon.awssdk:<service>`만 추가합니다.

## 테스트 단계

1. 요청 변환과 실패 정책을 단위 테스트합니다.
2. Floci에서 서비스 동작을 검증합니다.
3. emulator 공백이 문서로 확인된 기능만 LocalStack에서 실행합니다.
4. credentials와 정리 권한이 명확할 때만 제한된 AWS smoke test를 실행합니다.

## 근거 자료

- [DynamoDB repository](../../../../../aws-java/src/main/kotlin/io/bluetape4k/aws/dynamodb/repository/DynamoDbCoroutineRepository.kt)
- [S3 move 작업](../../../../../aws-java/src/main/kotlin/io/bluetape4k/aws/s3/S3AsyncClientCoroutinesExtensions.kt)
- [SQS coroutine 작업](../../../../../aws-java/src/main/kotlin/io/bluetape4k/aws/sqs/SqsAsyncClientCoroutinesExtensions.kt)

