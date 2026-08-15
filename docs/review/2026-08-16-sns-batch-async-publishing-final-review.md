# SNS 배치·비동기 퍼블리싱 최종 통합 리뷰

> 대상 이슈: #456
> Epic: #499
> 기준: `bd97ef16357a5cea93c10c60916d9bd54138409f`
> 구현 기준 HEAD: `69260b0`
> 리뷰일: 2026-08-16

## 범위와 판정

Java SDK v2, AWS Kotlin SDK, `aws-spring-boot` SNS `PublishBatch` 구현과
테스트·ABI fixture·문서·계획 추적성을 최종 대조했다. 1인 개발자 저장소이므로
human review gate는 N/A다. 다만 PR 생성, exact-head CI, merge 승인, local sync,
cleanup은 구현 완료와 분리된 후속 게이트다.

현재 구현 판정은 **PASS (P0=0, P1=0)** 이다. 남은 P2는 실제 AWS publisher
latency/cleanup telemetry와 heap·throughput 측정으로 [#515](https://github.com/bluetape4k/bluetape4k-aws/issues/515)에
분리했고, 공개 strategy/converter SPI 확장은 [#514](https://github.com/bluetape4k/bluetape4k-aws/issues/514)에
분리했다.

## 독립 관점 수렴

| 관점 | 최종 판정 | P0 | P1 | P2/P3 | 확인 내용 |
|---|---|---:|---:|---:|---|
| concurrency·backpressure·architecture | PASS | 0 | 0 | 0/1 | `Semaphore` credit과 rendezvous `Channel(0)`이 ordered pending을 worker 수 이내로 제한하고, scope 종료가 worker/collector를 정리한다. 성공 출력의 `completedEntryIds` O(N)은 반환 결과로 한정했다. |
| security·API·redaction | PASS | 0 | 0 | 0/1 | concrete SDK timeout 타입만 `TIMEOUT`으로 분류하고 임의의 `*Timeout*` class name은 `UNKNOWN`으로 남긴다. raw cause·payload·CR/LF는 Spring 예외에 노출하지 않는다. caller Job cancellation은 coroutine이 파생 예외를 만들 수 있어 type/message를 검증하고, 직접 publisher cancellation identity는 별도 테스트한다. |
| caller·retry·partial send | PASS | 0 | 0 | 0/0 | 순차 default fallback은 성공 prefix 후 중단하고 retry/rollback하지 않는다. 병렬 경로는 terminal response ID와 no-retry·FIFO/idempotency 책임을 보존한다. |
| tests·verification | PASS | 0 | 0 | 0/1 | assertion audit, ABI hash/classloader, cancellation, protocol, bounded pending, Kotlin compile, module test 증거가 최종 HEAD 이전에 생성되고 커밋됐다. |
| operations·release | PENDING | 0 | 0* | 2/0 | 기본 configuration-cache build는 저장소의 POM/fixture 직렬화 오류로 실패하고 no-cache build는 성공했다. PR·CI·merge·sync·cleanup은 아직 실행하지 않았다. |
| writer·docs·Kotlin patterns | PASS | 0 | 0 | 0/0 | 한·영 README에 Java SDK와 AWS Kotlin SDK의 서로 다른 request-entry 모델을 별도 예제로 제시했고, #514/#515 범위와 fallback 계약을 동기화했다. |

`*` operations의 P1은 구현 결함이 아니라 아직 수행하지 않은 delivery gate를 뜻하지
않는다. delivery 상태는 아래 DoD에서 별도로 관리한다.

## 테스트·정적 검증 증거

- 명시적 SNS assertion-audit targeted 목록:
  Java `15` (`issue-456-java-sns-assertion-audit-final.log`), AWS Kotlin `9`
  (`issue-456-kotlin-sns-assertion-audit-final.log`), Spring `40`
  (`issue-456-sns-spring-full-assertion-audit-final.log`)으로 총 **64 passing**,
  각 `BUILD SUCCESSFUL`.
- 기존 구현 경계의 명시적 SNS targeted 목록(`.lane-evidence/issue-456-targeted-final-v5.log`):
  **24 passing**, `BUILD SUCCESSFUL`.
- security/API remediation(`issue-456-security-remediation-final-v2.log`):
  **16 passing**, `BUILD SUCCESSFUL`; AWS Kotlin `compileTestKotlin` 포함.
- 세 모듈 전체(`issue-456-module-full-final-v3.log`, emulator skip):
  **359 passing**, `BUILD SUCCESSFUL`.
- `./gradlew detekt --no-daemon --no-configuration-cache`:
  `BUILD SUCCESSFUL` (`issue-456-detekt-final-v4.log`).
- `./gradlew build -x test --parallel --no-daemon --no-configuration-cache`:
  `BUILD SUCCESSFUL`, 52 actionable tasks (`issue-456-build-no-test-no-cache-final-v3.log`).
- 기본 `./gradlew build -x test --parallel --no-daemon`:
  configuration-cache 재사용 시 Maven POM/fixture task의 Gradle object 직렬화와
  `ConfigurationContainer.delegate is null`로 실패한다 (`issue-456-build-default-final-v3.log`).
  이는 이번 변경에서 새로 발생한 오류로 단정하지 않고 저장소 기존 검증 제한으로
  보존하며, no-cache 결과를 대체 증거로 사용했다.
- `git diff --check`: PASS.
- Java·AWS Kotlin·Spring SNS 테스트 전수 scan: `check(`, JUnit
  `assertThrows`, generic `assert(`, AssertJ/Kluent/kotlin.test assertion 잔존
  0건. `bluetape4k-projects/testing/assertions` 공개 API에 맞춰
  `shouldBeEqualTo`, `shouldBeLessOrEqualTo`, `shouldHaveSize`, `shouldBeEmpty`,
  `shouldBeSameInstanceAs`, `shouldNotContain`, `assertFailsWith`를 의미에 맞게
  사용했다. 기존 Spring auto-configuration 테스트의 `Map.size` 비교도
  `shouldHaveSize`로 정렬했다.
- ABI fixture SHA-256:
  `b8814d524f38f624ad8c51401286a694d64785ab352ecc1d301d186711c7d177`.
  `SnsOperationsBatchCompatibilityTest`가 hash와 isolated classloader identity,
  precompiled legacy default dispatch를 확인한다.

## 문서·추적성 검토

### 설계·계획·구현

- 설계 명세는 실제 `Channel(capacity = 0)`와 ordered-flush `Semaphore` credit
  조합, 정상 credit 반환과 cancellation scope 종료를 설명한다.
- 구현 계획 checkbox는 baseline, TDD RED→GREEN, ABI, bounded executor,
  assertion audit, 문서 parity를 완료로 갱신했고 PR/CI/merge 항목은 미완료로
  남겼다.
- 공개 `SnsOperations` fallback은 순차 단건 호출, 첫 non-cancellation 실패 중단,
  성공 prefix, `maxInFlightBatches = 1`, no-retry/no-rollback을 한·영 README에
  기록했다.

### Writer SPW-01..05

- **SPW-01 용어·언어:** 독자-facing 한국어 문장은 자연스럽게 유지하고 API,
  command, identifier, URL은 원문 토큰을 보존했다.
- **SPW-02 구조·추적성:** root/module README의 Java SDK와 AWS Kotlin SDK 예제를
  별도 block으로 분리하고 `#514/#515` 링크와 후속 범위를 양국 문서에 반영했다.
- **SPW-03 보안·redaction:** payload, ARN, raw SDK error, secret을 README 예제와
  public Spring exception 문자열에 넣지 않았다.
- **SPW-04 복사 가능성:** Java helper는
  `software.amazon.awssdk` entry를 사용하고 Kotlin helper는
  `aws.sdk.kotlin`용 `io.bluetape4k.aws.kotlin.sns.model` entry를 사용한다.
- **SPW-05 검증:** `git diff --check`, targeted/module test, detekt와 README
  heading/import 수동 parity read-back을 완료했다.

## 남은 위험과 후속 범위

- 실제 AWS publisher의 외부 latency·cleanup telemetry와 heap·throughput 수치는
  측정하지 않았다. #515에서 controlled publisher와 실제 측정 기준을 별도로
  설계한다.
- Spring Cloud AWS식 공개 `BatchExecutionStrategy`·converter 추상화는 현재
  converter/strategy 계약이 없어 도입하지 않았다. #514에서 API·의존성·호환성
  범위를 재검토한다.
- SNS partial send에는 business rollback/보상 트랜잭션이 없으므로 caller가
  terminal ID, FIFO deduplication 또는 외부 idempotency로 reconciliation해야 한다.
- 기본 configuration-cache build 오류는 별도 저장소 build infrastructure 후속으로
  다루며, 이번 구현의 no-cache 성공과 혼동하지 않는다.

## DoD Status

- [x] 설계·계획·리뷰 artifact와 구현 변경을 Lore commit으로 고정
- [x] Java/Kotlin/Spring API, ABI, redaction, cancellation, bounded concurrency 구현
- [x] `bluetape4k-assertions`/`bluetape4k-projects` 패턴으로 변경 테스트 전수 점검
- [x] SNS assertion-audit targeted 64 passing 및 모듈 전체 359 passing
- [x] detekt, Kotlin test compile, no-cache build, diff check, ABI hash 검증
- [x] #514/#515 후속 이슈와 실제 성능 측정 미검증 범위 기록
- [ ] PR 생성 및 Korean `## DoD Status` body read-back
- [ ] exact-head required CI 성공 및 live GitHub 상태 재검증
- [ ] fresh merge 승인, merge, local sync, proof-gated cleanup

**최종 상태: PENDING — 로컬 구현·검증은 완료했지만 PR·CI·merge·local sync·cleanup 게이트가 남아 있다.**
