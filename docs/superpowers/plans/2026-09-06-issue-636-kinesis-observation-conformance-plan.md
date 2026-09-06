# Issue #636 Kinesis 관측 conformance 구현 계획

> 승인된 설계를 TDD로 실행한다. native subagent는 현재 세션 정책상 사용하지 않으며 main session이
> spec, implementation, review와 delivery를 순차 소유한다.

## Task 0: checkpoint

- 설계, spec review, plan, plan review와 risk ledger를 terminology/diff audit한다.
- baseline Java/Kotlin Kinesis flow tests를 기록한다.
- Lore checkpoint를 commit한다.

## Task 1: shared manifest와 RED

**Files**

- Create: `conformance/kinesis-observation-v1/kinesis-observation-v1.properties`
- Modify: `aws-java/build.gradle.kts`, `aws-kotlin/build.gradle.kts`
- Create: 양 module `KinesisObservationConformanceTest.kt`

두 test source set에 같은 root resource 디렉터리를 연결한다. manifest version, vocabulary,
`token.length=24`, `count.max=10000`, token vectors와 representative lifecycle vectors를 읽는다.
아직 없는 canonical type/adapter 때문에 compile RED를 확인한다.

## Task 2: module-local canonical adapter GREEN

**Files**

- Create: 양 module `KinesisCanonicalObservation.kt`
- Test: Task 1 conformance tests와 기존 state tests

두 package에 동일 wire shape와 validation을 additive public API로 제공한다. Java legacy string/subtype과
Kotlin legacy enum/Observation을 canonical list로 변환한다. Java shard completion만 checkpoint/shard 두
event로 확장한다. token은 기존 redacted token prefix 24자를 사용하고 raw 값을 받지 않는다.

## Task 3: 실제 flow lifecycle conformance

**Files**

- Modify: 양 module `KinesisConsumerFlowUnitTest.kt`

기존 fake-client flow가 발행한 legacy event를 canonical adapter로 변환해 acquire→shard start→batch→
record→checkpoint/shard completion 순서와 cancellation·lease loss 경계를 검증한다. production consumer
event emission은 바꾸지 않는다.

## Task 4: 문서와 migration

**Files**

- Modify: `aws-java/README.md`, `aws-java/README.ko.md`
- Modify: `aws-kotlin/README.md`, `aws-kotlin/README.ko.md`
- Create: `docs/review/2026-09-06-issue-636-kinesis-observation-conformance-review.md`
- Create: `docs/lessons/2026-09-06-issue-636-kinesis-observation-conformance.md`
- Modify: `docs/lessons/README.md`

두 locale에 adapter 사용, canonical vocabulary, 24자 migration/cardinality와 token의 비보안 용도를
같은 구조로 기록한다. main-session six-lens fallback provenance와 P0/P1를 review에 남긴다.

## Task 5: 검증과 PR

순서대로 targeted tests, 양 affected module 전체 clean test, detekt, `compatibilityCheck`, publication
metadata/POM, terminology audit와 `git diff --check`를 실행한다. base `develop`, head
`feat/issue-636-kinesis-observation-conformance`, milestone `1.1.0`, assignee `debop`, issue labels를
mirror한 한국어 PR을 만들고 exact-head CI/review를 확인한다. merge는 세 PR 종합 승인 전 보류한다.

## Traceability

| 수용 조건 | Task |
| --- | --- |
| 동일 vocabulary/bounds/token | 1, 2 |
| 기존 sealed API와 ABI | 2, 5 |
| checkpoint/lease/cancellation matrix | 2, 3 |
| redaction/cardinality | 1, 2, 4 |
| migration 문서 | 4 |
| full validation/CI | 5 |
