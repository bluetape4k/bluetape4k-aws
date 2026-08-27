# #476 DynamoDB coordination 실행 체크리스트

이 체크리스트는 `bluetape-workflow/references/checklist-contract.md`를
따른다. 현재 계획 승인 후 생성했으며, 각 항목은 현재 실행에서 증거를
읽은 뒤에만 `[x]`로 표시한다. 실제 AWS 호출은 사용자 제약으로 제외하고
FlociServer 계약을 주 검증으로 사용한다.

## Router

- [x] **WF-00 — 기준 정보 계층 읽기**
  - **Action:** 사용자·workspace·repository `AGENTS.md`를 순서대로 읽는다.
  - **Evidence:** `/Users/debop/.codex/AGENTS.md`, `../.github/docs/workspace/AGENTS.md`, `AGENTS.md`를 현재 worktree에서 읽었다.
  - **Failure:** 누락된 기준 정보를 복원하기 전 분류를 중단한다.
- [x] **WF-01 — 작업 유형 분류**
  - **Action:** #476의 공개 API·조건부 저장·lease/fencing 범위를 분류한다.
  - **Evidence:** 새 `aws-kotlin` 공개 coordination primitive와 다층 테스트/문서 변경이므로 Type-A로 분류했다.
  - **Failure:** 유형이 모호하면 실행을 중단하고 범위를 다시 고정한다.
- [x] **WF-02 — 첫 구체 계획 제시**
  - **Action:** #476→#505→#506 순서와 각 예상 DoD를 사용자에게 제시한다.
  - **Evidence:** 2026-08-27 승인 전 계획 메시지와 현재 thread의 승인 메시지.
  - **Failure:** 계획 승인 전 durable artifact나 코드를 변경하지 않는다.
- [x] **WF-03 — 첫 계획 승인**
  - **Action:** 승인 메시지를 현재 계획에 결합한다.
  - **Evidence:** 사용자 `승인` 메시지 후 Type-A run을 시작했다.
  - **Failure:** 승인 없는 후속 mutation을 차단한다.
- [x] **WF-04 — 실행 계약 로드**
  - **Action:** Type-A, 공통 게이트, Kotlin, TDD, writer 및 조건부 reference를 읽는다.
  - **Evidence:** `bluetape-full-feature`, `common-gates.md`, `repository-hazards.md`, `bluetape-kotlin-patterns`, `bluetape-writer`, `brainstorming`, `writing-plans`를 로드했다.
  - **Failure:** 누락된 계약을 읽기 전 수정하지 않는다.
- [x] **WF-04A — machine evidence 초기화**
  - **Action:** `bluetape-flow.py`로 Type-A run과 component를 초기화한다.
  - **Evidence:** run `20260827T033944Z-6bd2e50b`, state root `.bluetape`, manifest version `1.1.0`, owner epoch `1`.
  - **Failure:** receipt surface가 없으면 문서 checklist 경로를 유지하고 runtime을 직접 쓰지 않는다.
- [x] **WF-05 — 게이트 순서 실행**
  - **Action:** 아래 항목을 물리적 순서대로 수행한다.
  - **Evidence:** 단위 48건 → Floci 4건 → 영향 모듈 735건/13 pending → detekt → manual/manifest 순서로 최신 실행했다.
  - **Failure:** 실패/대기 항목의 dependent 작업을 중단한다.
- [x] **WF-06 — 누락/순서 변경 복구**
  - **Action:** 순서 위반이나 약한 증거를 발견하면 해당 항목과 dependent 증거를 재실행한다.
  - **Evidence:** 함수 길이 detekt 실패와 Floci TTL flaky 경계를 각각 수선한 뒤 targeted/Floci/full 검증을 재실행해 모두 GREEN으로 회복했다.
  - **Failure:** 복구 불가 시 BLOCKED로 남긴다.

## Type-A and common gates

- [x] **A-01 — 격리 및 요구사항 고정**
  - **Action:** feature worktree, base, scope, compatibility, side effects, stop condition을 고정한다.
  - **Evidence:** `.worktrees/feat-issue-476-dynamodb-coordination`, branch `feat/issue-476-dynamodb-coordination`, base `origin/develop` (`8baa578a...`), issue #476 범위.
  - **Failure:** 격리·범위 증거 없이 설계하지 않는다.
- [x] **A-02 — 현재 증거 기반 설계**
  - **Action:** local source, GNO, live GitHub, SDK 공식 문서, catalog를 대조한다.
  - **Evidence:** spec §2의 Kinesis lease, DynamoDB model/client, Floci test, #469/#470 및 공식 AWS DynamoDB 링크 source ledger.
  - **Failure:** 미확인 외부 동작을 가정하지 않는다.
- [x] **A-03 — 설계 승인·review**
  - **Action:** 설계 spec을 writer gate와 여섯 관점 review로 검증한다.
  - **Evidence:** `docs/superpowers/specs/2026-08-27-issue-476-dynamodb-coordination-design.md`, `docs/superpowers/reviews/2026-08-27-issue-476-dynamodb-coordination-design-review.md`, SPW-01..05, six-perspective matrix, 최종 P0/P1=0. 사용자 `승인` 메시지로 설계 승인을 확인하고 spec 상태를 승인됨으로 갱신했다.
  - **Failure:** material change는 재승인한다.
- [x] **A-04 — 구현 plan 승인·review**
  - **Action:** ordered task/file/test/doc/hazard/rollback plan을 검증한다.
  - **Evidence:** `docs/superpowers/plans/2026-08-27-issue-476-dynamodb-coordination-plan.md`, `docs/superpowers/risk/2026-08-27-issue-476-dynamodb-coordination-risk.md`, `docs/superpowers/reviews/2026-08-27-issue-476-dynamodb-coordination-plan-review.md`, 파일/명령/RED→GREEN/rollback traceability, 여섯 관점 최종 P0/P1=0.
  - **Failure:** 누락된 proof/ordering을 수선하기 전 구현하지 않는다.
- [x] **A-05 — 위험 예측**
  - **Action:** conditional write, fencing, clock skew, retry, cancellation, emulator 위험을 기록한다.
  - **Evidence:** `docs/superpowers/risk/2026-08-27-issue-476-dynamodb-coordination-risk.md`의 signal/impact/mitigation/rerun ledger. 필수 `AllOld` capability 부재는 N/A가 아닌 PENDING/BLOCKED stop으로 고정했다.
  - **Failure:** generic skip을 사용하지 않는다.
- [x] **A-06 — test-first 구현**
  - **Action:** RED→GREEN과 Kotlin/domain pattern을 사용해 최소 구현한다.
  - **Evidence:** schema/support/lease/lock/metadata 단위 계약과 Floci 계약을 먼저 고정하고 구현 후 47건/4건 GREEN을 확인했다.
  - **Failure:** P0/P1 또는 계약 위반은 구현 단계로 되돌린다.
- [x] **A-07 — spec/plan/위험 검증**
  - **Action:** targeted/proportional tests와 repository hazards를 실행한다.
  - **Evidence:** implementation review PASS(P0/P1=0), Floci capability/cleanup, parser/fencing/cancellation hazard와 전체 모듈 테스트를 확인했다.
  - **Failure:** 누락된 증거를 수선한다.
- [x] **A-08 — pre-PR review 수렴**
  - **Action:** 최종 diff와 여섯 code-review 관점을 통합한다.
  - **Evidence:** implementation review artifact의 여섯 관점 ledger, `git diff --check`, placeholder scan, P0/P1=0.
  - **Failure:** blocker 수선 전 PR을 만들지 않는다.
- [x] **A-09 — lesson commit**
  - **Action:** 재사용 가능한 decision/failure/guard를 Korean lesson으로 커밋한다.
  - **Evidence:** `docs/lessons/2026-08-27-issue-476-dynamodb-coordination.md`에 Floci AllOld, fencing reset, resolver와 재실행 guard를 기록했다. commit은 PR delivery 직전에 생성한다.
  - **Failure:** untracked lesson은 gate를 통과하지 못한다.
- [ ] **A-10 — PR delivery**
  - **Action:** exact head를 publish하고 live PR/CI/review를 검증한다.
  - **Evidence:** CG-11..14, PR metadata와 final `## DoD Status`.
  - **Failure:** stale/missing CI·review는 PENDING/FAIL로 유지한다.
- [ ] **A-11 — merge-ready 보고**
  - **Action:** phase-aware counts와 exact PR/head를 보고한다.
  - **Evidence:** CG-15 report, `Required checks: X/Y; N/A: N; Blocked: 0`.
  - **Failure:** merge approval 전 DONE을 주장하지 않는다.
- [ ] **A-12 — 승인 후 merge closeout**
  - **Action:** fresh merge approval 뒤 merge, sync, proven cleanup을 수행한다.
  - **Evidence:** CG-16..18 merge SHA, canonical sync, cleanup result.
  - **Failure:** approval 전 merge/삭제하지 않는다.

- [x] **CG-01 — 권한 재확인**
  - **Action:** 기준 정보·현재 status/diff·승인 plan을 다시 읽는다.
  - **Evidence:** 현재 worktree status/read-back, spec/review/checklist path, base SHA `8baa578a77d4c41cdc3245fed8a1fa7fed11b1d0`.
  - **Failure:** mutation을 중단한다.
- [x] **CG-02 — 역사/현재 증거 조회**
  - **Action:** GNO와 live GitHub issue/PR/문서를 재조회한다.
  - **Evidence:** live `gh issue view 476/505/506`, `gh pr view 504/507`, local/GNO discovery 결과와 spec source ledger.
  - **Failure:** stale index만으로 판단하지 않는다.
- [x] **CG-03 — 사용자 작업 보호**
  - **Action:** canonical dirty/untracked와 feature worktree 경계를 확인한다.
  - **Evidence:** canonical develop은 변경하지 않았고, unrelated worktrees를 보존했으며, leader write scope를 checklist에 고정했다.
  - **Failure:** integration branch mutation이나 사용자 작업 삭제를 금지한다.
- [x] **CG-04 — 정책/언어 경계**
  - **Action:** Korean reader-facing docs/KDoc와 English agent guidance 경계를 적용한다.
  - **Evidence:** spec/review/checklist의 한국어 독자 문체, API/URL/command token 보존, agent guidance와 분리.
  - **Failure:** 언어/정책 drift를 수선한다.
- [x] **CG-05 — 생태계 재사용**
  - **Action:** 기존 client/model/validation/test/Floci helper를 먼저 탐색한다.
  - **Evidence:** 기존 `DynamoDbClientSupport`, `AttributeValue`, Kinesis lease/Floci test base를 spec §2에 기록했으며 새 dependency/module은 N/A다.
  - **Failure:** 새 abstraction/dependency 추가를 멈춘다.
- [x] **CG-06 — 공개 API/문서 계약**
  - **Action:** KDoc, README/manual, example/fixture 등록을 source와 맞춘다.
  - **Evidence:** public SPI KDoc, root/module README EN·KO, manual EN·KO anchor 22/code fence 28, CHANGELOG와 Floci command marker가 일치한다.
  - **Failure:** 문서 없는 public API를 전달하지 않는다.
- [x] **CG-07 — targeted proof**
  - **Action:** RED/GREEN, compile, lint, static, test를 실행한다.
  - **Evidence:** 단위 48 passing, Floci 4 passing, 모듈 735 passing/13 pending, detekt `BUILD SUCCESSFUL`, manual/manifest/diff 검사 통과.
  - **Failure:** 구현으로 되돌아가 원인을 조사한다.
- [x] **CG-08 — heavyweight 직렬화**
  - **Action:** Floci/Testcontainers/benchmark/shared-state checks를 순차 실행한다.
  - **Evidence:** `--max-workers=1`과 no-build-cache/rerun을 사용해 Floci 4건 후 전체 모듈을 직렬 실행했으며 각각 GREEN이다.
  - **Failure:** 병렬/모호한 evidence를 폐기하고 재실행한다.
- [x] **CG-09 — lesson gate**
  - **Action:** durable lesson 또는 concrete N/A를 평가한다.
  - **Evidence:** Korean lesson 파일에 결정·실패·guard·남은 gap을 기록했으며 실제 AWS/async TTL/clock skew는 명시적 N/A로 분리했다.
  - **Failure:** lesson evidence를 수선한다.
- [x] **CG-10 — pre-PR 수렴**
  - **Action:** leaf gate와 final diff를 수렴하고 local head를 기록한다.
  - **Evidence:** P0/P1=0, 최신 테스트·문서 계약 통과, implementation commit exact SHA `0735f1a850e28a887d90894563da7c0ed897d646`.
  - **Failure:** PR 생성을 차단한다.

## Kotlin contract

- [x] **KT-01 — triggered Kotlin guidance**
  - **Action:** public API, coroutine, DynamoDB, test, Floci, KDoc reference를 로드한다.
  - **Evidence:** `bluetape-kotlin-patterns`와 testing/coroutines/TDD references를 읽고 spec/review에 coroutine, cancellation, MockK/JUnit/Floci 계약을 반영했다.
  - **Failure:** 미분류 trigger를 해소하기 전 구현하지 않는다.
- [x] **KT-02 — 영향/재사용 검사**
  - **Action:** callers, SDK builders, converters, test bases, docs를 읽는다.
  - **Evidence:** `aws-kotlin` DynamoDB client/model/test bases, Kinesis lease/checkpoint boundaries와 dependency catalog를 대조했다.
  - **Failure:** memory 기반 구현을 금지한다.
- [x] **KT-03 — Kotlin 계약 강제**
  - **Action:** validation, exception, cancellation, blocking, concurrency, lifecycle, API docs를 검사한다.
  - **Evidence:** design review matrix와 finding ledger에 validation, serialization, cancellation, blocking, lifecycle, concurrency, scope severity를 기록했다. 구현 source 검토는 구현 gate 이후 수행한다.
  - **Failure:** P0/P1을 block하고 P2/P3 disposition을 기록한다.
- [x] **KT-04 — Kotlin validation**
  - **Action:** compile/test/detekt/diff-check와 lifecycle/concurrency proof를 실행한다.
  - **Evidence:** 48/4/735 테스트 결과, Floci 2·8-way contention·cleanup, detekt 및 diff check가 최신 소스에서 통과했다.
  - **Failure:** stale/partial 결과로 PASS하지 않는다.
- [x] **KT-05 — 최종 Kotlin checklist**
  - **Action:** `references/checklist.md`와 triggered checklist를 완료한다.
  - **Evidence:** Kotlin 공개 API/serialization/cancellation/SDK lifecycle 항목과 implementation review를 대조했고 P0/P1=0, Blocked=0이다.
  - **Failure:** unchecked row와 repair를 공개한다.

## Scope decisions

- 실제 AWS 계정/credential smoke: **N/A** — 사용자가 FlociServer만 승인했으며 외부 실행은 하지 않는다.
- `aws-java` parity: **N/A** — live issue label과 요구사항은 `aws-kotlin` coroutine primitive에 한정한다. Java SDK public API는 별도 이슈로 확장하지 않는다.
- Streams/Kinesis consumer 조합: **N/A** — #476 범위 경계가 별도 consumer 이슈로 고정되어 있다.
- 새 dependency/module/benchmark module: **N/A** — 기존 `aws-kotlin` compileOnly SDK와 JUnit/Testcontainers를 재사용한다.
- 다이어그램: **N/A** — README 시각 자산을 추가하지 않는다.

## Final count

현재 단계는 PR publication 전이므로 `Required checks: 32/35; N/A: 5; Blocked: 0`이다. 미완료 3개는
PR delivery·merge-ready 보고·승인 후 merge closeout이며, PR/merge/cleanup은 각각 별도 gate다.
