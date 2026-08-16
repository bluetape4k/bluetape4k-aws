# SQS template 비동기 자동 배치 구현 계획 통합 리뷰

## 범위와 게이트

- 대상 이슈: [#461](https://github.com/bluetape4k/bluetape4k-aws/issues/461)
- Epic: [#499](https://github.com/bluetape4k/bluetape4k-aws/issues/499)
- 구현 계획:
  `docs/superpowers/plans/2026-08-16-sqs-template-async-batching-plan.md`
- 승인 설계:
  `docs/superpowers/specs/2026-08-16-sqs-template-async-batching-design.md`
- 설계 리뷰:
  `docs/review/2026-08-16-sqs-template-async-batching-spec-review.md`
- 기준 브랜치: `origin/develop`
- 작업 브랜치: `feat/issue-461-sqs-template-batching`
- 설계 승인 commit: `bae9344a502eff9f1fb65188fd08a704823bc147`
- 검토 방식: Type A Step 3-R의 독립 관점 6개와 주 세션 통합 검토
- human review: 1인 개발 저장소이므로 N/A
- 종료 조건: 최신 계획에서 P0=0, P1=0이고 P2/P3의 처분과 구현 전 승인 경계가
  확정될 것

2026-08-16 live GitHub 재조회에서 Issue #461은 `OPEN`, assignee는 `debop`, milestone은
`0.6.0`이며 `enhancement`, `aws-spring-boot`, `sqs`, `spring-boot` 레이블을 유지했다.
GNO의 `bluetape4k-github`, `bluetape4k-docs`, `bluetape4k-wiki`에서는 이번 계획에 직접
재사용할 기록을 찾지 못해 저장소 소스, resolved dependency와 live GitHub 상태를 기준으로
검토했다.

## 최종 관점별 판정

| 관점 | 최종 lane | 판정 | 확인한 계약 |
|---|---|---|---|
| 성능·리소스 | `plan-performance-r2` | PASS, P0=0/P1=0/P2=0/P3=0 | bounded iterator snapshot, 호출별 window, 전역 permit, resident child/pending map 상한, orphan cleanup 관찰, 후속 성능 이슈 gate |
| 안정성·동시성 | `plan-stability-r2` | PASS, P0=0/P1=0/P2=0/P3=0 | `cancelIfIncomplete()` 단일 경계, close-aware permit acquire, accepted-before-handoff, monotonic deadline, close identity와 rollback |
| 보안 | `plan-security-r2` | PASS, P0=0/P1=0/P2=2/P3=0 | raw startup/cleanup throwable graph 제거, request/result/exception/log/metric redaction, trusted-caller payload 경계 |
| 운영 | `plan-operations-r2` | PASS, P0=0/P1=0/P2=0/P3=0 | exact Micrometer contract, Floci status artifact, `if: always()` 집계, rollback과 follow-up issue, PR/merge gate |
| 개발자·API | `plan-api-r2` | PASS, P0=0/P1=0/P2=0/P3=0 | 기존 API/ABI, pre-change fixture provenance, manager-missing failure, default marker, compileOnly publication |
| 사용자·문서·Kotlin | `plan-user-r2` | PASS, P0=0/P1=0/P2=0/P3=0 | canonical Kotlin/YAML 예제, README/manual parity, `Base58.randomString(16)`, 실제 bluetape4k assertions, 한국어 GitHub DoD |

보안 관점의 P2 두 건은 범위 누락이 아니라 명시적으로 수용한 운영 경계다. aggregate
payload/attribute byte와 동시 `sendMany` caller 수는 trusted application caller가 소유한다.
timeout 뒤 daemon cleanup thread의 장기 잔존 가능성과 실제 외부 latency, cleanup telemetry,
heap·throughput 측정은 PR 생성 전에 SQS 전용 후속 이슈를 만들도록 계획에서 차단했다. 이
이슈가 live read-back되기 전에는 해당 측정을 후속 범위로 미룬 PR을 만들 수 없다.

## P0/P1 수렴 기록

### 입력과 리소스 상한

- 입력 전체 `toList()`를 금지하고 iterator에서 `maxEntriesPerCall + 1`개만 읽는다.
- `maxEntriesPerCall` 상한으로 `+ 1` overflow를 막고 결과·resident child·pending map의
  구조적 상한을 별도 counter로 검증한다.
- 기본값을 승인 설계와 동일한 `maxEntriesPerCall=1_000`, `shutdownTimeout=5s`로 고정했다.

### 취소와 close 선형화

- stock `CompletionStage.await()`와 별도 cancel 조합을 금지하고 custom cancellable await를
  둔다.
- caller cancellation, child `finally`, close-timeout이 entry별 유일한
  `cancelIfIncomplete()` atomic guard를 공유해 library `cancel(false)`를 정확히 한 번만
  요청한다.
- permit과 close signal을 함께 기다리는 cancellable acquire를 사용한다. close가 먼저면
  placeholder와 외부 submit 없이 끝나고, permit이 먼저면 lifecycle lock의 `OPEN` 검사에서
  close와 선형화한다.
- barrier RED는 원래 `CancellationException` identity, cancel count 1, permit 누수 0,
  orphan child 0을 함께 검증한다.

### startup 보안과 optional class

- raw SDK/client startup failure identity 보존 요구를 제거했다. public
  `SqsBatchStartupException`은 startup component와 cleanup component kind/count만 보유하고
  `cause=null`, 빈 `suppressed`, 고정 message와 안전한 `toString()`을 사용한다.
- direct, enabled-manager-present, enabled-manager-missing nested configuration을 property로
  배타 분리한다. manager-missing guard는 `enabled=true`와
  `@ConditionalOnMissingClass(name=...)`에서 자원을 만들지 않고 safe startup exception으로
  실패한다.
- disabled mode와 custom operations는 manager class 없이 시작하고, enabled mode만 같은
  isolated classloader에서 명시적으로 실패하는 RED를 둔다.

### Spring bean과 publication 경계

- default direct/enabled configuration만 internal `DefaultSqsBatchOperationsMarker`를 만든다.
  Micrometer decorator는 marker, raw template, registry가 모두 있어야 생성되므로 custom
  concrete template을 장식하지 않는다.
- 기존 `SqsProperties`를 바꾸지 않고 pre-change bytecode fixture의 source hash, 승인 HEAD,
  parent, dirty 상태와 `javap`을 production 변경 전에 보존한다.
- `verifyAwsSpringSqsCompileOnlyPublication`은 spring-boot publication POM과 Gradle module
  metadata를 모두 검사하고 root `check`에 연결한다. missing metadata와 두 형식의 forbidden
  dependency fixture를 RED로 둔다.

### 운영·문서 검증

- meter 이름 `bluetape4k.aws.sqs.batch.operation`과 low-cardinality tag/outcome matrix를
  exact contract로 고정했다.
- Floci reporter는 missing/malformed/zero-test/mixed/skip XML을 구분하고
  `PASS`/`N_A`/`FAIL` status file을 남긴다. reporter와 artifact upload는 test 실패 뒤에도
  `if: always()`로 실행되며 `N_A`는 emulator PASS로 승격되지 않는다.
- canonical Kotlin marker와 YAML resource를 여섯 README/manual 문서가 exact compare한다.
  YAML에는 일곱 설정을 모두 포함한다.

## 명세에서 구현까지의 추적성

| 명세 경계 | 계획 Task | 선행 RED·완료 증거 |
|---|---|---|
| SDK 실제 API와 기존 `SqsProperties` ABI | 0 | dependency insight, resolved jar `javap`, pre-change fixture hash/load |
| public model·부분 결과·redaction | 1 | model/exception serialization, validation, raw token 비노출 |
| 별도 properties와 direct transport | 2 | binding failure, request capture, common normalizer |
| 실제 size/flush 자동 병합과 생성 rollback | 3 | manager clock/barrier, queue 분리, safe startup exception |
| bounded coordinator와 취소 | 4 | deterministic race, cancel identity/count, permit/pending peak |
| public operations와 close lifecycle | 5 | direct/batch parity, single deadline, concurrent close identity |
| Spring 조건·optional class·ABI | 6 | context matrix, missing-manager guard, custom backoff, fixture load |
| Micrometer | 7 | exact meter/tag/outcome와 secret 비포함 |
| Floci와 CI 상태 | 8 | sequential smoke, XML reporter fixture, always-run artifact |
| KDoc·README·manual | 9 | compiled example, exact canonical region/YAML parity |
| 통합·후속 이슈·rollback | 10 | targeted/module/detekt/build/manual/publication, live issue read-back |
| review·PR·merge train | 11 | Step 6-R, exact-head CI, fresh merge 승인, sync/cleanup |

모든 production 변경은 대응 RED 뒤에 배치했다. Task 0의 resolved SDK/ABI fixture는 다른
production 변경보다 먼저 실행하고, Task 11의 PR·merge는 구현 검증과 별도 fresh approval
gate로 유지한다.

## Writer·Kotlin 패턴 점검

- SPW-01: 구현자와 독립 리뷰어를 대상으로 Issue, Epic, branch, 승인 설계와 실행 경계를
  고정했다.
- SPW-02: 목표, 파일, RED/GREEN, 예상 실패, 검증 명령, rollback, commit과 GitHub DoD가
  task 순서로 이어진다.
- SPW-03: 사용자 협업 문서는 한국어 기술 문체를 사용하고 API, class, command, URL과
  machine token은 원문을 보존했다.
- SPW-04: 설계 요구를 Task 0~11과 exact test/Gradle/manual/CI 검증에 대응시켰다.
- SPW-05: Markdown heading, fence, placeholder, trailing whitespace와 rendered flow를 다시
  읽었다.
- Kotlin pattern: 테스트 ID는 `Base58.randomString(16)`을 사용한다. 비교·크기·범위·문자열은
  sibling `bluetape4k-projects/testing/assertions`의 실제 `shouldBeEqualTo`,
  `shouldHaveSize`, `shouldBeLessOrEqualTo`, `shouldBeGreaterOrEqualTo`, `shouldContain`,
  `shouldNotContain`을 사용하며 직접 matcher가 있는 boolean 우회 단언을 금지한다.

## 검증 범위

이번 단계는 구현 전 계획 검토이므로 production/test Kotlin compile, Floci와 full build는
실행하지 않았다. 설계 단계에서 기존 `SqsOperationsBatchTest`와
`SqsAutoConfigurationTest` 28개 baseline을 확인했고, 이번 단계에서는 resolved SDK/source,
live GitHub metadata, sibling assertions API, 문서 diff/fence/placeholder와 여섯 독립 관점의
최신 판정을 검증했다.

## DoD Status

- [x] 승인 설계와 Step 2-R 결과를 계획 입력으로 고정했다.
- [x] Task 0~11에 exact 파일, RED/GREEN, 명령, 예상 결과와 commit 경계를 기록했다.
- [x] 여섯 독립 관점의 최신 결과에서 P0=0, P1=0을 확인했다.
- [x] P2/P3의 반영, 수용 경계 또는 durable follow-up 시점을 기록했다.
- [x] actual bluetape4k assertions와 `Base58.randomString(16)`을 source에서 확인했다.
- [x] GNO와 live GitHub 상태를 재조회했다.
- [x] 계획·통합 리뷰를 Lore commit으로 보존한다.
- [x] 사용자가 구현 계획을 승인했다.

Final status: DONE — Step 3-R은 PASS이며 사용자가 구현 계획을 승인했다.
