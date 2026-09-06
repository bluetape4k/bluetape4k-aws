# Issue #635 SNS HTTP envelope 공통 정책 구현 계획

> **구현자 안내:** 승인된
> [`2026-09-06-issue-635-sns-envelope-policy-design.md`](../specs/2026-09-06-issue-635-sns-envelope-policy-design.md)를
> TDD로 실행한다. Kotlin 구현은 `$bluetape-kotlin-patterns`를 따르고, Gradle 명령은
> context-mode를 사용한다. native subagent는 현재 세션 정책상 사용하지 않으며 main session이
> 순차 실행과 통합 검증을 소유한다.

**목표:** Ktor와 Spring Boot가 같은 SNS HTTP decoded-envelope 정책과 같은 conformance
corpus를 사용하면서 기존 adapter API와 signature-verification 책임 경계를 유지한다.

**구조:** `aws-java`는 framework-neutral `SnsHttpEnvelopePolicy`, validated value,
rejection reason을 제공한다. 두 adapter는 strict Jackson decoder를 주입하고 기존 공개
`SnsHttpMessage`로 변환한다. `aws-java`의 Gradle test fixture variant가 한 corpus를 두
adapter 테스트에 제공한다.

**기술 스택:** Kotlin/JVM, Jackson 3, Gradle `java-test-fixtures`, JUnit 5,
bluetape4k assertions. 새 dependency와 실제 AWS/emulator 호출은 추가하지 않는다.

---

## Task 0: 문서와 구현 전 checkpoint

**Files:**

- Create: `docs/superpowers/specs/2026-09-06-issue-635-sns-envelope-policy-design.md`
- Create: `docs/superpowers/reviews/2026-09-06-issue-635-sns-envelope-policy-spec-review.md`
- Create: `docs/superpowers/plans/2026-09-06-issue-635-sns-envelope-policy-plan.md`
- Create: `docs/superpowers/reviews/2026-09-06-issue-635-sns-envelope-policy-plan-review.md`
- Create: `docs/superpowers/risk/2026-09-06-issue-635-sns-envelope-policy-risk.md`

- [ ] **Step 1: 설계와 plan의 traceability를 검토한다.**

  six-lens main-session fallback으로 P0/P1이 0인지 확인한다. spec의 각 수용 조건을 아래
  Task 1~5와 검증 명령에 연결한다.

- [ ] **Step 2: 한국어 기술 문체와 Markdown을 검사한다.**

  Run:

  ```bash
  node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
    docs/superpowers/specs/2026-09-06-issue-635-sns-envelope-policy-design.md \
    docs/superpowers/reviews/2026-09-06-issue-635-sns-envelope-policy-spec-review.md \
    docs/superpowers/plans/2026-09-06-issue-635-sns-envelope-policy-plan.md \
    docs/superpowers/reviews/2026-09-06-issue-635-sns-envelope-policy-plan-review.md \
    docs/superpowers/risk/2026-09-06-issue-635-sns-envelope-policy-risk.md
  git diff --check
  ```

  Expected: terminology finding 0건, whitespace error 없음.

- [ ] **Step 3: 구현 전 Lore checkpoint를 커밋한다.**

  Intent: `#635 SNS envelope 정책과 검증 경계를 구현 전에 고정한다`.
  `Tested`에는 baseline Ktor 6개/Spring 10개, spec/plan review, terminology, diff check를
  기록하고 `Not-tested`에는 production compile과 새 corpus를 기록한다.

## Task 1: core policy의 RED를 먼저 고정

**Files:**

- Create: `aws-java/src/test/kotlin/io/bluetape4k/aws/sns/SnsHttpEnvelopePolicyTest.kt`

- [ ] **Step 1: body limit과 decoder 호출 순서 RED를 작성한다.**

  ```kotlin
  var decodeCount = 0
  val error = assertFailsWith<SnsHttpEnvelopeValidationException> {
      SnsHttpEnvelopePolicy.parse("x".repeat(SnsHttpEnvelopePolicy.MAX_MESSAGE_BYTES + 1)) {
          decodeCount++
          emptyMap()
      }
  }
  error.reason shouldBeEqualTo SnsHttpEnvelopeRejectionReason.BODY_TOO_LARGE
  decodeCount shouldBeEqualTo 0
  ```

- [ ] **Step 2: valid normalization과 모든 reason RED를 작성한다.**

  `Notification`, 두 confirmation 타입, header trim/불일치, 필수/선택 문자열,
  topic ARN, URI syntax, signing scheme/userinfo/query/fragment/port/path/host/region/partition,
  type별 forbidden/required field를 map fixture로 검증한다. decoder exception은
  `INVALID_JSON`과 fixed message로 변환되는지 확인한다.

- [ ] **Step 3: RED를 실행한다.**

  Run:

  ```bash
  ./gradlew :bluetape4k-aws-java:test \
    --tests 'io.bluetape4k.aws.sns.SnsHttpEnvelopePolicyTest' --no-daemon
  ```

  Expected: 새 core 타입이 없어 compile RED.

## Task 2: core policy와 test fixture variant를 GREEN으로 구현

**Files:**

- Modify: `aws-java/build.gradle.kts`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/sns/SnsHttpEnvelopePolicy.kt`
- Create: `aws-java/src/testFixtures/kotlin/io/bluetape4k/aws/sns/SnsHttpEnvelopeConformanceFixtures.kt`

- [ ] **Step 1: additive core API를 최소 구현한다.**

  `SnsHttpEnvelopeType`, `SnsHttpEnvelopeRejectionReason`,
  `SnsHttpEnvelopeValidationException`, `SnsHttpEnvelope`,
  `SnsHttpEnvelopePolicy`를 만든다. public KDoc은 한국어로 작성하고 production import에는
  Ktor, Spring, Jackson이 없어야 한다.

- [ ] **Step 2: exact host와 redaction 계약을 구현한다.**

  `aws`/`aws-us-gov`는 4개 label, `aws-cn`은 5개 label을 요구한다. 모든 violation은
  fixed reason/message만 반환하고 입력값을 message에 삽입하지 않는다. 기존 header mismatch
  message만 호환을 위해 type/header 값의 closed vocabulary를 포함한다.

- [ ] **Step 3: test fixture variant와 단일 corpus를 만든다.**

  `aws-java/build.gradle.kts`에 `java-test-fixtures`를 적용한다. corpus data class는
  valid 기대 type/field와 invalid 기대 reason/message를 가진다. ASCII base JSON 뒤의 공백을
  이용해 정확히 256 KiB와 256 KiB + 1 byte case를 만든다.

- [ ] **Step 4: core GREEN과 fixture compile을 실행한다.**

  Run:

  ```bash
  ./gradlew :bluetape4k-aws-java:test \
    --tests 'io.bluetape4k.aws.sns.SnsHttpEnvelopePolicyTest' \
    :bluetape4k-aws-java:testFixturesClasses --no-daemon
  ```

  Expected: core policy test와 fixture compile PASS.

## Task 3: 두 adapter의 공통 corpus RED를 고정

**Files:**

- Modify: `aws-ktor/build.gradle.kts`
- Modify: `aws-spring-boot/build.gradle.kts`
- Modify: `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/sns/SnsHttpMessageParserTest.kt`
- Modify: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpMessageParserTest.kt`

- [ ] **Step 1: 두 module이 같은 fixture variant를 사용하게 한다.**

  각 module에 다음 test dependency만 추가한다.

  ```kotlin
  testImplementation(testFixtures(project(":bluetape4k-aws-java")))
  ```

- [ ] **Step 2: 같은 valid/invalid corpus test를 양쪽에 추가한다.**

  각 invalid case는 `SnsHttpEnvelopeValidationException`을 요구하고 `reason`, `message`,
  payload redaction을 확인한다. valid case는 adapter enum/value와 핵심 normalized field를
  확인한다.

- [ ] **Step 3: adapter RED를 실행한다.**

  Run:

  ```bash
  ./gradlew \
    :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.sns.SnsHttpMessageParserTest' \
    :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.sns.SnsHttpMessageParserTest' \
    --no-daemon
  ```

  Expected: Spring duplicate case 또는 extra-label host case가 기존 parser에서 기대 reason과
  다르게 실패해 behavioral RED. 단순 fixture/classpath 실패는 RED 증거로 인정하지 않는다.

## Task 4: adapter를 core policy에 연결해 GREEN

**Files:**

- Modify: `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sns/SnsHttpMessageParser.kt`
- Modify: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpMessageParser.kt`
- Test: 두 `SnsHttpMessageParserTest.kt`

- [ ] **Step 1: strict decoder를 adapter에 남긴다.**

  Ktor는 기존 injected `ObjectMapper`와 `maxMessageBytes`를 유지한다. Spring은 strict
  `JsonFactory`의 private `ObjectMapper`를 사용한다. 각 decoder는 JSON을
  `Map<String, Any?>`로 한 번만 읽고 core production은 Jackson type을 import하지 않는다.

- [ ] **Step 2: validated envelope를 기존 model로 변환한다.**

  Ktor `raw`는 기존처럼 top-level 문자열만 보존하고 나머지는 null로 만든다. Spring
  `raw`는 nested `MessageAttributes`를 포함한 map 복사본을 유지한다. adapter enum은
  `envelope.type.value`로 기존 `from`을 호출한다.

- [ ] **Step 3: 공통 corpus와 기존 parser test GREEN을 실행한다.**

  Run: Task 3의 명령을 그대로 다시 실행한다.

  Expected: Ktor/Spring 모두 같은 corpus 수와 reason으로 PASS, 기존 parser assertion PASS.

- [ ] **Step 4: 주변 Spring/Ktor 회귀를 실행한다.**

  Run:

  ```bash
  ./gradlew \
    :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.sns.*' \
    :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.sns.SnsHttpMessage*' \
    --no-daemon
  ```

  Expected: parser consumer, MessageAttributes, filter/resolver 관련 테스트 PASS.

## Task 5: 문서, 성능·ABI, 최종 검증과 PR

**Files:**

- Modify: `aws-ktor/README.md`
- Modify: `aws-ktor/README.ko.md`
- Modify: `aws-spring-boot/README.md`
- Modify: `aws-spring-boot/README.ko.md`
- Create: `docs/review/2026-09-06-issue-635-sns-envelope-policy-review.md`
- Create: `docs/lessons/2026-09-06-issue-635-sns-envelope-policy.md`
- Modify: `docs/lessons/README.md`

- [ ] **Step 1: README/KDoc 경계를 동기화한다.**

  두 locale은 공통 structural policy와 stable rejection reason을 설명하되 parser output이
  signature verification 결과가 아님을 분명히 한다. central manual을 복제하지 않는다.

- [ ] **Step 2: performance/stability와 API 호환을 확인한다.**

  source scan으로 decode 1회, pre-decode byte limit, raw reserialization 부재를 확인한다.
  기존 Ktor class constructor/parse와 Spring object/parse descriptor를 변경 전·후 비교한다.

- [ ] **Step 3: affected module 전체 검증을 순차 실행한다.**

  Run:

  ```bash
  ./gradlew :bluetape4k-aws-java:test --no-daemon
  ./gradlew :bluetape4k-aws-ktor:test --no-daemon
  ./gradlew :bluetape4k-aws-spring-boot:test --no-daemon
  ./gradlew :bluetape4k-aws-java:detekt \
    :bluetape4k-aws-ktor:detekt \
    :bluetape4k-aws-spring-boot:detekt --no-daemon
  ./gradlew compatibilityCheck --no-daemon
  git diff --check
  ```

  Expected: 모든 command PASS. emulator/실제 AWS 테스트는 순수 parser 범위라 N/A.

- [ ] **Step 4: lesson과 pre-PR review를 작성하고 검사한다.**

  lesson은 정책 공통화가 decoder 공통화와 다르며, corpus SSOT가 실제 drift를 막는다는 점을
  기록한다. review는 six-lens inline fallback provenance와 P0/P1=0을 기록한다.

- [ ] **Step 5: Lore commit과 PR을 생성한다.**

  Issue #635의 assignee, milestone `1.1.0`, labels를 mirror한다. PR base는 `develop`, head는
  `feat/issue-635-sns-envelope-policy`다. 한국어 body는 `Fixes #635`를 포함하고 마지막
  `## DoD Status`에서 exact-head CI 전 항목을 `PENDING`으로 표시한다.

## Spec-to-task traceability

| Spec 수용 조건 | Plan task |
| --- | --- |
| framework-neutral core policy와 dependency 경계 | Task 1, Task 2 |
| 기존 adapter API/ABI 유지 | Task 4, Task 5 |
| 단일 corpus와 동일 reason | Task 2, Task 3, Task 4 |
| duplicate/host/partition/size/field 거부 | Task 1~4 |
| pre-decode limit와 single decode | Task 1, Task 4, Task 5 |
| redacted reason/message | Task 1~4 |
| KDoc/README structural/signature 경계 | Task 4, Task 5 |
| tests/detekt/compatibility/CI | Task 5 |

## Rollback

- core policy가 adapter API를 보존하지 못하면 adapter wiring과 core API를 함께 되돌린다.
- test fixture variant가 module publication/build를 깨뜨리면 production policy는 유지한 채
  root shared test resource source-set 대안으로 plan/spec를 다시 승인한다.
- exact host allowlist가 공식 endpoint와 충돌하면 추측으로 완화하지 않고 근거와 corpus를
  갱신한 뒤 security 관점을 다시 검토한다.
