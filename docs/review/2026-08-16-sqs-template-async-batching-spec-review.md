# SQS template 비동기 배치·부분 실패 결과 설계 통합 리뷰

## 범위와 기준

- 대상 이슈: [#461](https://github.com/bluetape4k/bluetape4k-aws/issues/461)
- Epic: [#499](https://github.com/bluetape4k/bluetape4k-aws/issues/499)
- 검토 대상: `docs/superpowers/specs/2026-08-16-sqs-template-async-batching-design.md`
- 기준 커밋: `2ff6b957fee97ffbdca6ca842af3d98bdbeaddf5`
- 작업 브랜치: `feat/issue-461-sqs-template-batching`
- 검토 방식: Type A Step 2-R의 독립 관점 6개와 주 세션 통합 검토
- 종료 조건: 최신 명세에서 P0=0, P1=0이고 모든 P2/P3의 처분이 확정될 것

2026-08-16 실시간 재조회에서 Issue #461은 `OPEN`, assignee는 `debop`, milestone은
`0.6.0`이며 `enhancement`, `aws-spring-boot`, `sqs`, `spring-boot` 레이블을 유지했다.
연결 PR은 아직 없고 이번 단계는 구현 전 설계 검토만 다룬다.

## 근거

| 구분 | 확인한 내용 |
|---|---|
| 저장소 기준선 | `SqsOperations`, `SqsCoroutinesTemplate`, `SqsSendRequest`, `SqsBatchModels`, `SqsProperties`, `SqsAutoConfiguration`, `MicrometerSqsOperations`의 현재 계약과 호출 경계를 대조했다. |
| 기존 회귀 테스트 | 격리 worktree에서 `SqsOperationsBatchTest`와 `SqsAutoConfigurationTest` 28개가 통과했고 `BUILD SUCCESSFUL`을 확인했다. |
| AWS SDK | 중앙 catalog가 해석한 SQS SDK `2.51.3`의 `SqsAsyncBatchManager`, `BatchOverrideConfiguration`, `RequestBatchManager.close()` 소스를 확인했다. manager close는 drain이 아니라 pending future를 취소할 수 있다는 전제로 설계했다. |
| coroutine 취소 | kotlinx-coroutines `1.10.2`의 `CompletionStage.await()`가 미완료 future에 `cancel(false)`를 요청하는 소스를 확인했다. |
| Spring Cloud AWS | `BatchingSqsClientAdapter`와 전용 template 사용 지침을 비교해 listener가 쓰는 표준 `SqsAsyncClient`와 batching client를 분리했다. |
| assertions | `bluetape4k-projects/testing/assertions`의 `shouldBeEqualTo`, `shouldHaveSize`, `shouldBeLessOrEqualTo`, `shouldBeGreaterOrEqualTo`, `shouldContain`, `shouldNotContain` 실제 선언을 확인했다. |

## 최종 관점별 판정

| 관점 | 최종 lane | 판정 | 확인한 계약 |
|---|---|---|---|
| 성능 | `spec-performance-r3` | PASS, P0=0/P1=0 | 호출별 admission window, template 전역 permit, bounded snapshot/result, queue URL별 manager 분리와 측정 가능한 검증 항목 |
| 안정성 | `spec-stability-r3` | PASS, P0=0/P1=0 | caller cancellation identity, placeholder admission race, 단일 shutdown deadline, concurrent close completion, manager/executor cleanup |
| 보안 | `spec-security-r3` | PASS, P0=0/P1=0 | request/result/exception/log/metric redaction, service code allow-list 정규화, trusted serialization 경계, payload byte 검증 책임 |
| 운영 | `spec-operations-r2` | PASS, P0=0/P1=0 | opt-in default, direct fallback, startup rollback, timeout 반환, classloading 격리, observability, 운영 rollback |
| 개발자·API | `spec-api-r3` | PASS, P0=0/P1=0 | 별도 `SqsBatchOperations`, 기존 `SqsProperties` ABI 보존, 생성자 불변성, normalizer, cleanup exception identity |
| 사용자·호출자 | `spec-user-r3` | PASS, P0=0/P1=0 | RETURN/THROW와 delete 결과, 같은 호출의 FIFO 순서 비보장, strict-order 대체 경로, 설정 trade-off, 문서 예제 검증 |

최종 통합 심각도는 P0 0건, P1 0건, 미처리 P2 0건, P3 0건이다. 각 lane이 제안한
P2는 아래와 같이 명세에서 직접 해소했으며 별도 미처리 항목으로 남기지 않았다.

## 주요 수정과 처분

### 성능과 backpressure

- 입력 전체를 한꺼번에 coroutine task로 만들지 않고 `maxInFlightEntries` 이하의
  admission window로 나눈다.
- template 전역 semaphore가 활성 SDK future를 제한하고, `maxEntriesPerCall`이 호출별
  snapshot/result entry 수를 제한한다.
- payload aggregate byte는 신뢰할 수 있는 애플리케이션 호출자, 기존
  `SqsSendRequest`, AWS SDK와 SQS service limit의 책임으로 남겼다. entry 개수 상한을
  byte 상한으로 잘못 설명하지 않는다.

### 취소와 수명 주기

- caller job의 원래 `CancellationException` identity를 최우선으로 보존하고 미완료
  future에는 `cancel(false)`만 요청한다. 이미 manager가 수락한 메시지의 전달 rollback은
  보장하지 않는다.
- placeholder를 lifecycle lock 아래 먼저 등록해 submit 전 close race도 drain 대상에
  포함한다.
- `System.nanoTime()`으로 deadline을 한 번 만들고 모든 drain, manager close wait,
  scheduler termination이 남은 시간을 공유한다.
- manager close는 이름 있는 daemon cleanup thread에서 실행한다. 이 thread는 늦은
  `Throwable`을 내부에서 정규화해 uncaught exception이나 raw cause 로그를 남기지 않는다.
- scheduler 뒤 manager 생성, manager 뒤 transport/template 조립 중 어느 단계가 실패해도
  이미 만든 resource를 역순으로 정리하고 원래 startup failure를 보존한다.

### API와 호환성

- 기존 `SqsOperations`, `SqsCoroutinesTemplate`, listener와 public `SqsProperties`는
  변경하지 않는다. opt-in 설정은 별도 `SqsBatchProperties`에 둔다.
- public data class는 private primary constructor,
  `@ConsistentCopyVisibility`, secondary constructor validation, snapshot, redacted
  `toString()`, `Serializable`, `serialVersionUID` 계약을 함께 갖는다.
- transport failure의 `code`는 항상 `null`이다. service failure만 정규화한 code 또는
  `UNKNOWN`을 제공한다.
- non-null `sequenceNumber`의 blank 값을 거부하고, 문자열 검증은 실제 값을 message에
  넣지 않는 bluetape4k `requireNotBlank` lazy-message overload를 사용한다.
- response normalizer는 expected entry ID와 비교해 누락, 미지, 중복 outcome을 거부한다.
  구조화된 result는 상관관계용 entry ID를 유지하지만 `toString()`, exception message,
  로그와 metric에는 노출하지 않는다.

### 사용자 계약과 문서

- `sendMany`는 `RETURN`과 `THROW`를 제공하고 `deleteMany`는 entry failure를 항상
  `SqsDeleteManyResult`로 반환한다. 자동 selective retry는 제공하지 않는다.
- 같은 `sendMany` 호출에서도 child submit이 병렬이므로 동일 FIFO group의 전송·전달
  순서를 보장하지 않는다. 엄격한 순서가 필요하면
  `SqsCoroutinesTemplate.send(request)`를 한 건씩 await하거나 caller가 순서를 소유하는
  raw `SqsAsyncClient.sendMessageBatch`를 사용한다.
- root와 `aws-spring-boot` module의 영문·한글 README, 영문·한글 manual을 함께 갱신한다.
- `SqsBatchDocumentationExampleTest`의 canonical source region을 컴파일하고 manual
  contract script가 Markdown fenced snippet과 비교하도록 설계해 예제 복사를 방치하지
  않는다.

## 리뷰 lane 복구 기록

첫 performance, stability, security lane은 liveness 상태 전이와 native 작업 순서가
일치하지 않아 PASS 근거로 사용하지 않았다. workflow receipt에서 기존 lane을
`replaced`로 fencing하고 각각 replacement lane을 새로 실행했다. 이후 수정된 최신 명세를
대상으로 `spec-performance-r3`, `spec-stability-r3`, `spec-security-r3`를 다시 실행했다.
API, 운영, 사용자 관점도 P1 수정 뒤 r2/r3 lane을 실행했다. 최종 표에는 terminal receipt가
있는 최신 replacement/rereview 결과만 반영했다.

## 구현 단계에서 증명할 항목

설계 PASS가 구현 PASS를 뜻하지는 않는다. 다음 항목은 구현 계획의 RED/GREEN과 검증
명령에 반드시 연결한다.

1. size/flush interval 병합, queue URL 분리, partial/transport failure, direct fallback
2. mutable input snapshot, caller cancellation identity, underlying future `cancel(false)`
3. placeholder admission race, concurrent/repeated close, 전체 shutdown deadline, startup rollback
4. 기존 `SqsProperties`의 변경 전 bytecode fixture와 manager class가 없는 direct-mode
   application context
5. Base58 ID와 bluetape4k assertions를 사용한 단언, redaction·serialization·Micrometer tag
6. README/manual canonical example compile·동일성 검사와 locale contract
7. Floci send/FIFO/delete/close smoke test; entry partial failure를 안정적으로 만들 수 없으면
   fake manager contract test를 기준 증거로 사용하고 emulator capability gap을 명시

실제 heap·throughput 수치는 구현 전인 현재 측정할 수 없다. 구현 계획에서 active future,
pending placeholder와 호출별 entry 수의 구조적 상한을 먼저 검증하고, 구현 후
performance/stability scan에서 수치 측정이 필요한 회귀 신호가 확인되면 별도 측정 이슈를
즉시 등록한다. 현재는 구현과 측정 기준값이 없어 독립된 성능 P2를 남기지 않는다. 구현
검토에서 측정을 후속 범위로 미룬다면 이슈 등록 전에는 PR 생성 단계로 진행하지 않는다.
측정 없이 성능 향상을 주장하지 않는다.

## Writer·Kotlin 패턴 점검

- SPW-01: 구현자와 리뷰어를 대상으로 Issue #461, 기준 커밋, 명세, 저장소 소스,
  SDK/coroutine 근거와 미구현 항목을 고정했다.
- SPW-02: 문제, 대안, 공개 API, 실패 모드, 호환성, 운영, 테스트, 문서, 리뷰 처분과
  최종 판정을 연결했다.
- SPW-03: 한국어 기술 문체를 사용하고 API, class, property, command, URL은 원문 토큰을
  보존했다.
- SPW-04: 각 관점의 P0/P1과 P2 권고를 최신 명세의 구체적인 수정에 대응시켰다.
- SPW-05: 최종 Markdown과 명세를 다시 읽고 placeholder, trailing whitespace,
  `git diff --check`를 검증했다.
- Kotlin 설계 점검: validation exception, cancellation, resource ownership, public data class,
  KDoc, Base58와 assertions 계약을 구현 전 기준으로 고정했다. Kotlin 구현 변경은
  아직 없으므로 compile/LSP 결과는 구현 단계에서 증명한다.

## DoD Status

- [x] 여섯 독립 관점과 주 세션 통합 검토를 완료했다.
- [x] 최신 명세에서 P0=0, P1=0을 확인했다.
- [x] 모든 P2 권고를 명세에 반영하거나 구현 검증 항목으로 책임과 시점을 고정했다.
- [x] 첫 review wave의 liveness 순서 오류를 fencing과 replacement로 복구했다.
- [x] 설계·리뷰 문서의 한국어 기술 문체와 근거 추적성을 검증했다.
- [ ] 사용자가 최종 설계를 승인한다.

Final status: PENDING — Step 2-R은 PASS이며 사용자 설계 승인이 남아 있다.
