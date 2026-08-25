# #541 SNS batch 확장 설계 명세 독립 검토

> 검토 대상: `docs/superpowers/specs/2026-08-24-issue-541-sns-batch-extensions-design.md`
> 저장소: `bluetape4k-aws`
> 기준 브랜치: `develop` (`fe24e60204d74d730bd189d2c67f260b1d834f79`)
> 검토일: 2026-08-24
> 범위: 구현 전 설계 명세와 수용 기준

## 검토 목적과 증거

이 검토는 #541의 Phase 1 strategy와 Phase 2 converter가 기존 SNS batch
계약을 우회하지 않는지 확인하고, public ABI·compileOnly classpath·취소·부분
성공·민감 정보 비노출·성능 경계를 구현 전에 잠그는 것을 목적으로 한다.

확인한 근거는 다음과 같다.

| 근거 | 확인 내용 |
| --- | --- |
| GitHub Issue #541 | OPEN, `enhancement`·`aws-spring-boot`·`sns`, milestone `1.0.0`, assignee `debop`인 현재 요구와 1번 선택 |
| `SnsOperations.kt` | 기존 순차 fallback, 첫 실패 중단, cancellation identity, transport redaction |
| `SnsCoroutinesTemplate.kt` | 기존 2-인자 생성자와 typed batch 호출 경로 |
| `SnsBatchExecutor.kt` | 10개 chunk, bounded worker, ordered result, sibling cleanup, protocol guard |
| `SnsBatchModels.kt` | request의 `topicArn` 단일 소유, typed ID/FIFO 검증, defensive copy |
| `SnsBatchExceptions.kt` | payload·ARN·원문 오류 비노출과 현재 `completedEntryIds` 의미 |
| `aws-spring-boot/build.gradle.kts`, `gradle/libs.versions.toml` | `spring-messaging` 미도입과 compileOnly dependency 경계 |
| 기준선 테스트 | `SnsOperationsBatchCompatibilityTest`와 `SnsBatchExecutorTest` 12개 통과 |
| 공식 문서 | AWS `PublishBatch`의 10개·262,144-byte 제한, Spring `Message` payload/header 경계 |

기준선 실행은 다음 명령으로 수행했다.

```text
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests 'io.bluetape4k.aws.spring.sns.SnsOperationsBatchCompatibilityTest' \
  --tests 'io.bluetape4k.aws.spring.sns.SnsBatchExecutorTest'
```

결과는 `BUILD SUCCESSFUL`, 12개 테스트 통과다. 이 증거는 구현 완료나 전체
Gradle 검증을 의미하지 않는다.

## 독립 관점과 findings

세 개의 read-only 관점(performance, stability/security, API/user/operations)이
각각 명세를 읽었다. P0는 없었고, 아래 표의 수정으로 P1은 모두 해소했다.

| 심각도 | finding | 반영 위치 | 처분 |
| --- | --- | --- | --- |
| P1 | 전체 실행을 외부 strategy에 넘기면 10개 분할·동시성·protocol·redaction을 우회할 수 있음 | 설계 §승인된 결정 1, §실패·취소, §ABI lifecycle | `SnsBatchExecutionPort`를 library-owned guarded port로 고정했다. 1~10개·request ID subset·중복 claim·active claim 상한·no queue·aggregate result 검증을 호출 시점에 강제하고, raw client/future/credential/retry를 노출하지 않는다. |
| P1 | ID·header·attribute shape와 우선순위가 구현 불가능하게 모호함 | 설계 §Header allowlist, §변환 오류 | exact header constants, case-sensitive 비교, explicit ID 우선·`UUID` 타입·`MessageHeaders.ID` fallback, typed `Map<String, MessageAttributeValue>`와 defensive copy를 고정했다. |
| P1 | serializer 예외의 cause/message가 payload나 secret을 누출할 수 있음 | 설계 §변환 오류, §원자적 preflight | `SnsBatchMessageConversionException`이 safe enum·field·index만 보관한다. `CancellationException`만 동일 instance로 전파하고 그 밖의 topic/serializer/header/iterator 원인은 cause 없이 정규화한다. strategy 원인도 `STRATEGY_FAILURE`로 정규화한다. |
| P1 | 새 hot path에 성능 수용 기준이 없음 | 설계 §테스트·검증 매트릭스와 성능 경계 fixture | fake publisher `N=1_000`, `maxInFlightBatches` 1/2/8, 최소 3회 반복, `ceil(N/10)`, active chunk+pending claim 상한, resident entry 상한, no queue, default executor 동등성을 명시했다. 실제 AWS latency/throughput은 N/A다. |
| P2 | eager `Iterable` 변환이 무한 입력과 취소를 제한하지 못함 | 설계 §Converter 선택, §원자적 preflight | 유한 `Collection`, `maxMessages` 선검사, `suspend` serializer, 항목 전후 `ensureActive()`, iterator 오류 정규화를 고정했다. 입력 보유 비용은 항목 수와 payload 총 길이에 비례한다. |
| P2 | custom strategy의 client·future·detached job lifecycle이 모호함 | 설계 §Client와 coroutine lifecycle, §Strategy misuse | client는 호출자 소유로 고정하고 strategy의 client 생성·교체·close·`GlobalScope`·raw thread를 금지했다. 취소/transport 시 child와 SDK future drain을 기다린다. |
| P2 | public constructor와 factory 선택이 미정 | 설계 §Strategy 주입, §Converter 선택, §ABI | template은 기존 `(SnsAsyncClient, SnsProperties)`와 새 3-인자 secondary constructor만 제공한다. converter descriptor는 `()`와 `(SnsPayloadSerializer)`로 고정하고 factory는 추가하지 않는다. |
| P2 | 운영 rollback과 stacked PR/release-pinned manual 경계 부족 | 설계 §구현 단계와 PR 경계, §문서·운영 변경, §롤백 | Strategy→Converter branch topology, explicit constructor canary, publish 중단·drain·default rollback, redacted low-cardinality telemetry, `releaseRef: 0.5.0` 유지와 `Unreleased/develop` 문서 경계를 명시했다. |

## 남은 P2와 후속 연계

SNS `PublishBatch`의 262,144-byte 개별·전체 payload 제한은 공식 문서에 따라
확인했지만, 현재 converter 설계는 정확한 wire-size 계산을 구현 범위에 넣지
않았다. `maxMessages`는 항목 수만 제한하며 serializer의 큰 payload를 자동으로
줄이지 않는다. 따라서 다음 후속 이슈 후보를 별도 acceptance criteria로
분리한다.

- SNS batch payload byte-size preflight와 attribute 크기 계산
- Jackson3 opt-in serializer와 media type 정책
- ByteArray/large payload 지원
- strategy별 benchmark/telemetry 및 retry/idempotency 안전 설계

이 후보들은 현재 public API와 dependency/ABI가 실제 구현으로 안정화된 뒤
생성한다. 이번 명세 승인이나 구현에 자동으로 포함하지 않는다.

## 명세 self-review와 writer gate

다음 검사를 명세와 이 검토 기록에 적용했다.

| 항목 | 결과 | 증거 |
| --- | --- | --- |
| SPW-01 audience·purpose·evidence | PASS | 두 문서의 독자·목적·저장소 경로·Issue·공식 URL·불확실성 기록 |
| SPW-02 artifact contract | PASS | 설계의 책임 경계·API·실패·호환성·테스트·DoD와 검토의 finding·처분·잔여 위험 |
| SPW-03 Korean technical register | PASS | 한국어 기술 문체와 exact API/명령/URL 보존 |
| SPW-04 technical traceability | PASS | 저장소 근거 표, 독립 finding별 명세 위치와 처분, 기준선 실행 결과 |
| SPW-05 rendered read-back | PASS | Markdown heading/table/code fence와 최종 상태를 다시 읽고 확인 |
| KO-01 evidence 보존 | PASS | 수치·식별자·명령·URL·N/A 범위를 변경하지 않음 |
| KO-02 hollow claim 제거 | PASS | 성능·안전성 주장을 fake fixture와 상한으로 제한 |
| KO-03 translationese 제거 | PASS | 책임·실패·상태 경계를 직접 동사로 기술 |
| KO-04 register·terminology | PASS | strategy, converter, claim, queue, payload 등 용어를 문서 전체에서 일관되게 사용 |
| KO-05 voice·humor | PASS | 홍보성 표현과 비유 없음 |
| KO-06 reader-facing surface | PASS | 표·코드·링크·DoD·상태 문구 read-back |
| KO-07 contextual audit | PASS | `audit-korean-terms.mjs --json` findings 0 |

추가 정적 확인 결과는 trailing whitespace 0, code fence 10개(짝수),
미완성 placeholder 표기 0이다. 현재 변경은 설계와 검토 문서만 포함하고
production code, test code, Gradle, README, manual을 수정하지 않았다.

## Verdict와 다음 gate

- **P0: 0**
- **P1: 0 (모두 명세에 반영)**
- **P2: 1개 잔여 주제(바이트 크기 preflight)를 후속 이슈로 명시**
- **결론: 설계 명세는 구현 계획으로 전환할 수 있으나, 사용자 written-spec 승인 전에는 구현하지 않는다.**

현재 workflow의 다음 gate는 사용자가 설계 문서와 이 검토 기록을 읽고 승인하는
것이다. 승인 뒤에만 implementation plan, TDD 테스트, Phase 1 strategy, Phase 2
converter를 순서대로 진행한다. PR 생성·merge·release는 이 검토 범위에 없다.

## DoD Status

- 설계·독립 검토: **PASS — P0/P1 없음, P2 후속 범위 명시**
- 기준선: **PASS — SNS batch 회귀 12개**
- 구현·전체 검증: **PENDING — written-spec 승인 후 수행**
- PR·merge·release: **PENDING — 현재 권한 범위 밖**
