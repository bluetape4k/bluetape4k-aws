# #542 SQS registry lifecycle 회귀 수정 실행 계획

> **실행 규칙:** 이 계획은 승인된 선형 stacked PR train의 첫 child인
> #542에서 실행한다. 각 단계는 순서대로 완료하고, 실패하면 원인을
> 기록한 뒤 같은 단계의 검증을 통과할 때까지 다음 단계로 진행하지 않는다.

**Goal:** 개별 SQS listener 중지가 registry 전역 lifecycle 상태를
잘못 내리지 않도록 수정하고, touched test를 bluetape4k assertions와
Kotlin 테스트 패턴으로 정렬한다.

**Architecture:** 전역 SmartLifecycle 진입 상태와 실제 활성 listener ID를
분리한다. 개별 stop은 해당 ID만 제거하고, 전역 stop만 전체 상태를 닫는다.
기존 비동기 drain callback과 stopping 중 start 거부 계약은 유지한다.

**Tech Stack:** Kotlin, Spring Boot SmartLifecycle, AtomicBoolean,
ConcurrentHashMap.newKeySet, JUnit 5, MockK, bluetape4k-assertions,
Gradle.

## 사전 조건과 산출물

- 작업 브랜치: fix/issue-542-sqs-registry
- base: develop의 승인 시점 HEAD
- 대상 구현: aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsMessageListenerContainerRegistry.kt
- 대상 테스트: aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsMessageListenerContainerRegistryTest.kt
- 설계 기준: docs/superpowers/specs/2026-08-25-ecosystem-patterns-stack-design.md

## Task 1: 다중 컨테이너 회귀 테스트를 먼저 작성

1. 기존 registry 테스트를 읽고 두 mock container의 start/stop callback
   제어 지점을 고정한다.
2. 두 container를 시작한 뒤 첫 container만 개별 stop하고, 첫 callback이
   정확히 한 번 완료되는지 확인한다.
3. 개별 stop 직후 registry가 여전히 running이고 두 번째 container가
   활성임을 assertion한다.
4. 전역 stop을 호출하여 두 번째 container의 stop이 요청되고 전역
   callback이 한 번 완료되는지 확인한다.
5. touched test의 예외 검증 import와 호출은
   io.bluetape4k.assertions.assertFailsWith를 사용한다.
6. 고정 sleep은 사용하지 않고 CompletableFuture 또는 latch callback과
   await/assertion으로 동기화한다.

검증:

~~~text
./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.sqs.SqsMessageListenerContainerRegistryTest' --no-daemon --console=plain
~~~

예상 결과: 새 회귀 테스트가 현재 구현에서 RED가 된다. RED가 아니면
테스트가 결함을 관찰하지 못한 것이므로 테스트를 수정한 뒤 다시 실행한다.

## Task 2: registry lifecycle 상태를 최소 범위로 수정

1. 전역 gate용 lifecycleStarted와 실제 활성 ID 집합 activeIds를 추가한다.
2. register와 전역 start에서 start 성공 후 해당 ID를 activeIds에 넣는다.
3. 개별 stop에서는 해당 ID만 activeIds에서 제거하고 lifecycleStarted는
   유지한다.
4. 전역 stop에서는 lifecycleStarted를 내리고 activeIds를 비운다.
5. isRunning은 activeIds가 비어 있지 않은지 반환한다.
6. stoppingIds, registry lock, callback once 보장을 유지한다.
7. suspend 함수나 cancellation을 삼키는 runCatching을 추가하지 않는다.
8. production code에는 !!를 사용하지 않고 기존 로그/예외 계약을 보존한다.

검증:

~~~text
./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.sqs.SqsMessageListenerContainerRegistryTest' --no-daemon --console=plain
~~~

## Task 3: 모듈 회귀와 정적 패턴 검증

1. Spring Boot 모듈 전체 테스트를 실행한다.
2. 변경 diff에 raw JUnit exception assertion, fixed sleep, production !!
   유입이 없는지 확인한다.
3. Kotlin formatting과 detekt에 걸리는 불필요한 abstraction을 제거한다.
4. git diff --check를 실행한다.

검증:

~~~text
./gradlew :bluetape4k-aws-spring-boot:test --no-daemon --console=plain
./gradlew detekt --no-daemon --console=plain
rg -n 'org\.junit\.jupiter\.api\.assertThrows|Thread\.sleep|delay\(' aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsMessageListenerContainerRegistryTest.kt
rg -n '!!' aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsMessageListenerContainerRegistry.kt
git diff --check
~~~

## Task 4: 7-Tier review와 검증 receipt 작성

다음 관점으로 source-read-only review를 작성한다.

1. Correctness: 개별 stop, 전역 stop, register race, callback once
2. API/ABI: public registry method signatures와 lifecycle contract
3. Concurrency: lock, atomic state, stoppingIds, activeIds visibility
4. Ecosystem: bluetape4k assertions와 기존 helper 사용
5. Tests: RED/GREEN 및 full module evidence
6. Performance/resource: 추가 순회·집합 비용과 callback 누수
7. Maintainability: 이름, KDoc/주석, diff 범위와 future train 영향

산출물:

- docs/review/2026-08-25-issue-542-sqs-registry-review.md
- PR body 마지막의 DoD Status
- issue #542 링크와 정확한 base/head SHA

## Task 5: Lore commit, push, PR 생성

1. docs 설계/계획 commit과 구현 commit을 분리한다.
2. 모든 commit message는 intent, Constraint, Rejected, Confidence,
   Scope-risk, Directive, Tested, Not-tested trailers를 포함한다.
3. 구현 branch를 origin에 push한다.
4. #542를 연결한 Korean PR을 base develop, head
   fix/issue-542-sqs-registry로 생성하고 debop, milestone, labels를
   issue와 맞춘다.
5. PR body 마지막은 DoD Status 섹션으로 끝낸다.
6. exact-head CI, review, issue link를 읽어 merge-ready 상태만 보고한다.
   merge 또는 다음 child 구현은 fresh merge approval 전에는 진행하지 않는다.

## 실패·롤백 기준

- RED 테스트가 결함을 재현하지 못하면 Task 1에서 멈추고 테스트 계약을
  보강한다.
- callback이 중복 호출되거나 activeIds가 race로 유실되면 Task 2를
  되돌리지 말고 해당 단계에서 원인을 수정한 뒤 targeted test를 반복한다.
- full module 또는 detekt가 실패하면 실패 로그를 review artifact에 남기고
  PR을 만들지 않는다.
- 외부 AWS credential이 필요한 smoke 실패는 이 PR의 local proof와
  분리하여 Not-tested로 기록한다.

## 추적성

- Epic: 1.0.0 ecosystem 정렬 Epic, native sub-issue #542
- 이슈: #542
- 구현: SqsMessageListenerContainerRegistry와 해당 테스트
- 설계: ecosystem-patterns-stack-design
- 검증: targeted registry test, Spring Boot module test, detekt, diff check

## DoD Status

- 상태: 승인된 계획, 구현 전
- 완료: 목표, 파일, 순서, 명령, 실패 기준, review 관점을 고정함
- 미완료: Task 1–5 실행, Epic/child 관계 readback, PR/CI
