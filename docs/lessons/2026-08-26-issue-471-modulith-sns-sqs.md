# 이슈 #471 Spring Modulith SNS·SQS 외부화 lesson

## 배경

Spring Modulith event publication을 SNS·SQS로 외부화하고 SQS delivery를 local
application event로 복원하는 선택적 Spring Boot adapter를 구현했다. 실제 AWS 계정은
사용하지 않고 `bluetape4k-testcontainers`의 `FlociServer`로 local transport 계약을
검증했다. human review는 1인 개발자 조건에 따라 N/A로 두고 독립 agent review와
source-backed test evidence로 대체했다.

## 결정 또는 발견 사항

- 외부 event는 stable type/version/event ID와 final concrete JVM class를 registry에
  명시한다. classpath scanning과 default typing은 사용하지 않으며 interface, abstract,
  non-final class 등록을 configuration error로 거부한다.
- producer는 bounded in-flight semaphore와 application-owned scope를 사용하고 종료 시
  admission을 닫은 뒤 accepted job을 제한 시간 안에 drain한다. Spring Modulith
  publication completion은 transport future의 실제 완료와 연결한다.
- consumer는 handler 성공 또는 이미 완료된 duplicate만 acknowledge한다. active claim,
  handler 실패, source 검증 실패, renew·complete mutation 실패는 no-ack으로 남겨 SQS
  visibility와 redrive policy가 retry·DLQ를 결정하게 한다.
- 결과가 불확실한 claim mutation 뒤에는 claim을 즉시 release하지 않는다. lease 만료 후
  takeover와 fencing token을 사용해야 ambiguous completion에서 중복 dispatch를 줄일 수
  있다.
- 기본 in-memory idempotency store는 단일 process 검증용이다. 여러 instance와 재시작
  경계에는 `AwsModulithEventIdempotencyStore` durable 구현 bean을 제공해야 한다.
- 신규 auto-configuration import는 feature property만 보지 말고 repository-wide
  `@ConditionalOnAwsEnabled`도 반드시 적용한다. 전체 module test가 이 누락을 targeted
  test보다 먼저 드러냈다.
- FIFO message group의 `Mutex`는 동시 실행만 막을 뿐 수신·submission 순서를 보장하지
  않는다. handler마다 mutex 획득을 경쟁시키지 말고 submission 시점에 predecessor
  ticket을 연결해야 같은 그룹 순서와 다른 그룹 병렬성을 함께 보장할 수 있다.
- Floci는 SQS, SNS-to-SQS fanout transport, redrive, ack, claim/fencing을 검증하지만
  production SNS certificate/signature telemetry, IAM, cross-account, 실제 AWS timing을
  증명하지 않는다.

## 결과

registry, codec, SNS·SQS target publisher, externalization transport, DIRECT/verified SNS
consumer, claim store, metrics, auto-configuration, consumer fixture와 EN/KO manual을
추가했다. Task 10에서는 concurrency stability test, final concrete security guard,
global disable guard와 6개 관점 code review evidence를 수렴했다.

주요 검증 수렴 커밋은 `124f7fe4`이며, 이전 단계의 원자적 구현 커밋과 함께 이슈 #471
범위를 구성한다.

## 검증

- concurrency stability: 3개 `@RepeatedTest(100)`, 300 tests 통과
- 전체 Modulith + Floci: 19 classes, 489 tests 통과
- SQS FIFO ordering 회귀: 100회 반복, listener container 116 tests 통과
- 전체 `aws-spring-boot` + Floci: 177 classes, 1,397 tests, failure/error 0,
  기존 skip 2
- module detekt와 consumer fixture compile 통과
- configuration·dispatch internal constructor 금지 fixture는 예상한 compile error 확인
- EN/KO terminology findings 0, manifest current, manual contract 9 runs/44 assertions 통과
- `build -x test --parallel --no-configuration-cache`: 65 tasks 통과

기본 configuration-cache build는 Dokka plugin classloader의
`kotlinx/serialization/StringFormat` 누락으로 구성 단계에서 한 차례 실패했다. 같은
source를 configuration cache 없이 검증해 Issue #471 code failure와 분리했다.

최초 hosted exact-head run `33037601682`에서 기존 FIFO 직렬화 test 1건이 실패했다.
재실행만으로 green을 만들지 않고 scheduler 경쟁을 원인으로 확정한 뒤 ordering ticket과
100회 회귀 검증을 추가했다.

## 향후 지침

event serializer를 호출하기 전 registry가 안전한 concrete target을 확정해야 하며,
역직렬화 뒤 exact-class 검사만으로 gadget side effect를 막았다고 판단하지 않는다.
claim mutation의 성공 여부가 불확실할 때 cleanup을 선의로 추가하지 말고 fencing과
lease takeover 계약을 먼저 검토한다. auto-configuration을 새 imports에 넣을 때는 global
disable test를 함께 실행한다. FIFO 그룹 직렬화는 mutual exclusion과 ordering을 별도
불변식으로 검토한다. emulator 결과는 서비스별 지원 범위와 실제 AWS 미검증 항목을 같은
문서에 기록한다.
