---
title: Spring Boot 예제 emulator polling 표준화
date: 2026-08-12
issue: 487
module: aws-spring-boot-examples
---

# Issue #487 Spring Boot 예제 emulator polling 표준화

## 배경

Spring Boot DynamoDB 예제는 테이블이 `ACTIVE`가 될 때까지 deadline loop와
`delay(500)`을 사용했고, SQS/SNS 예제는 30초 동안 같은 고정 지연을 반복했다.
에뮬레이터 기동과 메시지 listener 처리 시간이 환경마다 다르므로, 고정 지연은
불필요한 대기와 timeout 원인 추적의 어려움을 함께 만들 수 있다.

## 발견 사항

- 저장소의 `io.bluetape4k.junit5.awaitility.untilSuspending`은 suspend 조건을
  코루틴 polling으로 실행하고 `ConditionFactory`의 전체 timeout과 poll 설정을
  따른다.
- 두 예제 모듈의 `testImplementation(bt4k.bluetape4k.junit5)`가 이 helper를
  이미 제공하므로 새 dependency나 ad-hoc polling 유틸리티가 필요하지 않다.
- `examples/aws-ktor-sqs-examples`의 `delay(100)`은 이번 이슈의 Spring Boot
  범위에 포함되지 않는다.
- `AwsExposedPluginTest`의 `Thread.sleep(5_000)`은 non-cooperative close를
  재현하는 fixture이므로 polling 코드로 바꾸면 안 된다.

## 결정

- DynamoDB 테이블 상태 polling을 `await.atMost(Duration.ofSeconds(30))`
  및 100ms `pollInterval`을 사용하는 `untilSuspending`으로 전환한다.
- SQS/SNS의 `waitUntil`도 같은 30초·100ms 계약으로 전환하고, `alias`와
  마지막 Boolean 결과를 timeout 메시지에 포함한다.
- DynamoDB timeout 메시지에는 테이블 이름, 전체 timeout, poll interval, 마지막
  `TableStatus`를 포함하고, 원래 `IllegalStateException` 계열 실패 계약을
  유지한다.
- Floci를 기본 backend로 먼저 실행하고, LocalStack은 명시적 fallback으로
  순차 실행한다.
- Ktor의 `delay(100)`과 의도적인 `Thread.sleep(5_000)` fixture는 보존한다.

## 결과

두 Spring Boot 예제 테스트에서 고정 `delay(500)` loop를 제거하고 repository
표준 suspend polling helper를 사용하게 됐다. polling 실패 시 조건 설명,
timeout, poll interval, 마지막 관측 결과를 확인할 수 있고, `ConditionTimeoutException`
의 cause가 있으면 함께 보존한다.

## 검증

- 두 모듈 `compileTestKotlin`: 성공.
- Floci 전체 모듈 테스트(순차): DynamoDB 3개, SQS 1개 통과.
- LocalStack 전체 모듈 테스트(순차): DynamoDB 3개, SQS 1개 통과.
- 영향 범위 밖 고정 대기 확인: Ktor SQS의 `delay(100)`과
  `AwsExposedPluginTest`의 `Thread.sleep(5_000)`은 의도대로 남아 있다.
- `git diff --check`와 Kotlin 최종 scope review를 통과했다.

## 향후 지침

Spring Boot AWS 예제에서 외부 emulator 또는 listener 상태를 기다릴 때는
고정 `delay` 반복문 대신 bounded `untilSuspending`을 사용한다. timeout은
전체 대기 시간과 poll interval을 명시하고, 도메인 상태를 관측하는 조건은
마지막 상태를 실패 메시지에 남긴다. 고정 sleep이 resource lifecycle이나
비협조적 fixture를 재현하는 경우에는 polling 규칙을 적용하지 않고 해당
의도를 주석과 lesson에서 보존한다.
