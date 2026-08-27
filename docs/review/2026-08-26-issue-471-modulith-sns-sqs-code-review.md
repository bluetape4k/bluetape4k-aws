# 이슈 #471 Spring Modulith SNS·SQS 구현 검토

작성일: 2026-08-27

## 검토 범위

- 대상: `feat/issue-471-modulith-sns-sqs`
- 기준: `origin/develop` 대비 이슈 #471 구현·테스트·manual 변경
- 검토 기준 데이터: `57415f4a8734bee9deb8a97a015ff2140b63ad84`와 Task 10 worktree 변경
- 범위 제외: 실제 AWS 계정, IAM, cross-account, production SNS 인증서 회전·전파 지연
- 로컬 서비스 검증: `bluetape4k-testcontainers`의 `FlociServer`
- 판정 규칙: P0/P1은 0이어야 하며 P2/P3는 수정하거나 근거와 후속 경계를 기록한다.

## 구현 근거 원장

| 영역 | 소스 근거 |
| --- | --- |
| opt-in과 lifecycle | `AwsModulithEventsAutoConfiguration`, `AwsModulithEventExternalizationTransport` |
| event registry와 codec | `AwsModulithEventTypeRegistry`, `DefaultAwsModulithEventCodec` |
| SNS·SQS producer/consumer | `AwsModulithSnsTargetPublisher`, `AwsModulithSqsTargetPublisher`, `AwsModulithSqsEventConsumer` |
| claim·fencing·idempotency | `AwsModulithEventIdempotencyStore`, `InMemoryAwsModulithEventIdempotencyStore` |
| 진단과 metrics | `AwsModulithExceptions`, `AwsModulithMetrics` |
| 외부 consumer ABI | `AwsModulithConsumerFixture`, 금지 constructor compile fixtures |
| 사용자 계약 | EN/KO `storage-and-messaging.md`, README recipe, 설계·계획 문서 |

## 6개 관점 판정

| 관점 | 상태 | P0/P1 | 판정 근거와 처분 |
| --- | --- | --- | --- |
| Performance | PASS | 0/0 | 독립 검토의 P2 두 건과 P3 한 건은 `beforeJobStart`, `metrics()`, `awaitIdle()` internal test seam/helper에 한정된다. production admission은 bounded semaphore와 `maxInFlight`를 사용한다. test hook 예외 hardening과 O(1) test metrics는 현 public 계약 blocker가 아니므로 후속 최적화로 보류한다. |
| Stability | PASS | 0/0 | 독립 검토가 renew·complete 일반 실패 후 `release()` 부재를 P1로 제시했으나, ambiguous mutation 뒤 stale claim을 release하지 않고 lease-expiry takeover에 맡기는 승인 설계와 fencing 계약에 반한다. sub-ms heartbeat는 validated 30초 이상 lease에서 불가능하고 store SPI는 non-blocking·cancellation-cooperative 구현을 요구한다. 100회 반복 concurrency와 전체 Modulith suite로 race·cleanup을 재검증했다. |
| Security | PASS | 0/0 | 최초 독립 검토의 P1은 property-name denylist가 wrapper type ID를 완전히 막지 못한다는 지적이었다. registry가 interface·abstract·non-final class를 거부하도록 RED→GREEN으로 수정해 serializer 호출 전 final concrete target을 고정했고, 수정 후 독립 재검토가 PASS했다. event ID·production size bound는 기존 registry/property validation에 있으며 header와 sanitizer P2는 승인된 no-leak 계약을 유지한다. |
| Operator/Ops | PASS | 0/0 | 독립 재검토에서 `@ConditionalOnAwsEnabled`, root opt-in, `destroyMethod="close"`, listener lifecycle 위임, consumer 생성 전 redrive 검증을 확인했다. manual은 Floci와 실제 AWS 증거를 분리하고 DLQ·claim·shutdown 진단을 설명한다. |
| Developer/API | PASS | 0/0 | 독립 재검토에서 public registry, final concrete exact-class 계약, Kotlin consumer fixture와 공개 ABI 참조를 확인했다. configuration·event·source·claim exception code는 bounded diagnostic 계약을 유지하고 internal constructor는 외부 compile fixture에서 차단된다. |
| User/caller | PASS | 0/0 | 최초 독립 검토에서 Modulith 절의 Floci 경계와 ack/retry/DLQ·custom idempotency bean 연결 공백 P1 두 건을 확인했다. EN/KO manual에 해당 계약을 추가한 뒤 재검토한다. BOM, runtime SDK, registry, opt-in, DIRECT/SNS source mode, FIFO와 함께 한 경로에서 찾을 수 있으며 human review는 1인 개발자 조건에 따라 N/A다. |

## 발견 사항 처분

### 수정 완료

1. 신규 auto-configuration에 repository-wide `@ConditionalOnAwsEnabled`가 누락돼 global disable test가 실패했다. isolated RED 2 tests/1 failure 뒤 annotation을 추가했고 isolated GREEN 2/2와 전체 module suite로 닫았다.
2. polymorphic/default typing serializer가 concrete-class postcheck 전에 subtype을 생성할 수 있는 보안 P1을 확인했다. registry가 final concrete JVM class만 허용하도록 회귀 테스트를 먼저 추가하고 구현을 최소 수정했다.
3. final concrete 제약을 KDoc와 EN/KO manual에 명시해 runtime configuration failure를 사용자가 사전에 이해하도록 했다.
4. Modulith manual에 custom durable idempotency bean override, 성공·완료 중복의 ack, 실패·active claim의 no-ack/retry/DLQ, lease-expiry takeover, Floci 검증 범위와 실제 AWS 미검증 경계를 추가했다.

### 기각 또는 후속 보류

- renew·complete 실패 후 즉시 release: 결과가 불확실한 mutation에서 중복 dispatch를 촉진할 수 있어 승인된 lease-expiry takeover/fencing 계약을 유지한다.
- heartbeat sub-ms busy loop: public properties가 lease를 30초 이상으로 검증하므로 production 입력에서 재현되지 않는다.
- heartbeat join timeout: store SPI가 non-blocking·cancellation-cooperative suspend 구현을 요구하며 adapter가 임의 timeout으로 외부 저장소 의미를 바꾸지 않는다.
- transport test hook·test metrics·`awaitIdle()` 최적화: internal deterministic test surface이며 production P0/P1이 아니다.
- outbound event ID 누락: `AwsModulithResolvedRegistration.eventId()`가 blank, 128 UTF-8 bytes, control character를 이미 검증한다.
- codec 절대 상한 누락: production auto-configuration은 `AwsModulithEventsProperties.Producer.validate()`의 1..262,144 범위를 통과한 값만 전달한다.
- header control character와 sanitizer diagnostics: 현재 승인 계약은 1,024 UTF-8 byte bound와 민감 payload 비노출이다. 공개 계약 변경 없이 확대하지 않는다.

## 검증 증거

| 검증 | 결과 |
| --- | --- |
| concurrency stability | 3개 `@RepeatedTest(100)`, 300 tests, failure/error/skip 0 |
| 전체 Modulith + Floci | 19 test classes, 489 tests, failure/error/skip 0 |
| 전체 `aws-spring-boot` + Floci | 177 test classes, 1,298 tests, failure/error 0, 기존 skip 2 |
| security/registry 회귀 | 33 tests, failure/error/skip 0 |
| static/consumer ABI | module detekt PASS, `compileAwsSpringModulithConsumerFixture` PASS |
| 금지 ABI | configuration·dispatch internal constructor fixture가 예상한 compile error로 FAIL |
| build | `./gradlew build -x test --parallel --no-configuration-cache --no-daemon` PASS; 65 tasks, 64 functional tests PASS |
| manual | manifest current, manual contract 9 runs/44 assertions PASS, EN/KO parity PASS |

Floci 결과는 local transport, routing, redrive, ack, claim·fencing 계약의 증거다. 실제
AWS 계정이 없으므로 IAM, cross-account, production SNS signature/certificate telemetry,
real AWS timing은 검증하지 않았고 Floci green을 해당 증거로 확대하지 않는다.

기본 configuration-cache build는 프로젝트 구성 전에 Dokka plugin classloader의
`kotlinx/serialization/StringFormat` 누락으로 한 차례 실패했다. 같은 source를
`--no-configuration-cache`로 재실행해 전체 build와 functional tests가 통과했으므로
Issue #471 compile/test 결함으로 분류하지 않으며 Gradle plugin cache 호환성 공백으로
기록한다.

## Writer·증거 게이트

| Gate | 상태 | 증거 |
| --- | --- | --- |
| SPW-01 source ledger | PASS | public symbol과 manual section을 근거 원장에 연결했다. |
| SPW-02 scope/status | PASS | 구현·검증·범위 제외와 실제 AWS 미검증을 분리했다. |
| SPW-03 commands/results | PASS | 실행한 명령의 count와 terminal 결과를 기록했다. |
| SPW-04 examples/contracts | PASS | consumer fixture와 EN/KO recipe를 public surface와 대조했다. |
| SPW-05 readback | PASS | 6개 관점 발견 사항을 수정·기각·후속 보류로 모두 처분했다. |
| Human review | N/A | 1인 개발자 조건이며 independent agent/code evidence로 대체했다. |

## DoD Status

- [x] Task 10 concurrency·lifecycle 안정성 검증
- [x] performance, stability, security, Ops, developer/API, user/caller 6개 관점 검토
- [x] P0=0, P1=0
- [x] Floci·module·static·consumer ABI·build·manual 검증
- [x] 실제 AWS와 Floci 증거 경계 기록
- [x] human review N/A 기록
- [ ] PR exact-head hosted CI와 merge gate

최종 판정: **PASS**. Task 10 local 구현·검토는 완료 가능하며, PR 생성 뒤 hosted
exact-head CI와 별도 merge 승인만 후속 gate로 남는다.
