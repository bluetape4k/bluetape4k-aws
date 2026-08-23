# Issue #463 S3 ResourceLoader 구현계획 3-R 통합 리뷰

## 리뷰 범위와 판정 기준

- 대상: `docs/superpowers/plans/2026-08-23-issue-463-s3-resource-loader.md`
- 상위 근거: 승인된 설계 `docs/superpowers/specs/2026-08-23-issue-463-s3-resource-loader-design.md`, 설계 3-R 리뷰 `docs/review/2026-08-23-issue-463-s3-resource-loader-spec-review.md`
- 범위: 단일 literal bucket, exact protocol, wildcard pattern, Spring auto-configuration, client·stream 수명, IAM·오류 경계, Floci-first 검증, 문서와 publication contract
- 관점: Security/Permission, Performance/Resource, Stability/Lifecycle, Developer/API, Operator/Ops, User/Caller
- 우선순위: P0/P1은 구현계획 커밋 전에 0건이어야 하며, P2는 수정하거나 승인된 잔여 위험과 검증 방법을 명시한다.
- 이번 리뷰는 구현·Floci·CI를 실행한 결과가 아니라 계획의 실행 가능성과 검증 경계를 판정한다.

## 관점별 결과

| 관점 | P0 | P1 | P2/P3 | 통합 판정과 근거 |
| --- | ---: | ---: | ---: | --- |
| Security / Permission | 0 | 0 | 0 | parser가 provider와 AWS 호출보다 먼저 실행되고 malformed·cross-bucket·root 입력을 0-call로 거부한다. bounded diagnostic, PUA sentinel 충돌, endpoint isolation, 단일 bucket IAM 예시가 계획에 있다. |
| Performance / Resource | 0 | 0 | P2 1 / P3 0 | `gradle.projectsEvaluated` 이후 임시 init script가 기존 `-Xmx*`를 제거하고 `maxHeapSize = "256m"` 및 마지막 `-Xmx256m`을 설정한다. `Runtime.maxMemory()` assertion과 stdout 증거가 heap 적용을 검증한다. all-page 상한 금지는 승인 설계의 잔여 비용 위험으로 유지하고 50×1,000×4 fixture와 10분 예산으로 검증한다. |
| Stability / Lifecycle | 0 | 0 | 0 | object delete → bucket delete → context close 순서, Spring-managed/external client의 close 소유권, suppressed cleanup failure, targeted → performance → bounded Floci wrapper → full non-emulator 순서, `set -euo pipefail` fail-fast와 첫 실패 기준 데이터 보존이 명시돼 있다. |
| Developer / API | 0 | 0 | 0 | public constructor와 method contract, `@Throws(IOException::class)` Java reflection RED/GREEN, type-based custom backoff, fixed-name qualifier와 예약 이름 충돌을 명시했다. provider는 matcher 준비 뒤 한 번만 조회한다. |
| Operator / Ops | 0 | 0 | 0 | `debop` owner, Issue #463/Epic #500 escalation, CI/Nightly 10분 job·artifact, no-new-workflow 경계, cache-isolated POM/metadata 생성·validator·파일 존재·runtime S3 grep 명령이 있다. |
| User / Caller | 0 | 0 | 0 | exact와 pattern 사용 경로, `@Value`, `ApplicationContext.getResources(...)` 자동 interception 제외, custom/direct caller 책임, stream/client/context 수명, exact GetObject(HEAD 포함)와 pattern ListBucket+prefix IAM 차이를 README/manual에 고정했다. |

## 통합된 수정 및 잔여 위험

| ID | 발견 | 계획 반영 | 상태 |
| --- | --- | --- | --- |
| R-01 | pattern listing all-page 소비는 짧은 prefix에서 비용·heap을 키울 수 있다. | 숨은 결과 상한을 추가하지 않고 승인된 계약을 유지한다. imperative loop, 단일 `HashSet`, 1회 정렬·배열 생성, 50×1,000×4 constrained-heap 검증과 IAM prefix 문서를 요구한다. | 승인된 P2 잔여 위험 |
| R-02 | repository 공통 Test worker의 `-Xmx4G`가 `JAVA_TOOL_OPTIONS`를 덮을 수 있다. | `gradle.projectsEvaluated` 이후 기존 `-Xmx*` 제거, `maxHeapSize = "256m"`, 마지막 `-Xmx256m`, `Runtime.maxMemory()` assertion과 실행 로그 보존으로 수정했다. | 해결 |
| R-03 | Spring client와 resolver가 이중 close할 수 있다. | context-managed client는 context가 1회 close하고, `destroyMethod = ""` 외부 client는 caller가 1회 close하도록 분리했다. | 해결 |
| R-04 | pattern resolver custom subtype·fixed-name·unrelated bean의 backoff/충돌이 모호할 수 있다. | S3-specific subtype은 type-based backoff, replacement는 `s3ResourcePatternResolver` 예약 이름, unrelated bean의 동일 이름은 명확한 collision failure로 고정했다. | 해결 |
| R-05 | Kotlin checked exception이 Java caller에 노출되지 않을 수 있다. | `@Throws(IOException::class)`와 reflection declared-exception assertion을 RED와 GREEN 양쪽 순서에 배치했다. | 해결 |
| R-06 | caller가 exact와 pattern IAM·API 경계를 오해할 수 있다. | `@Value`/exact resource와 직접 주입하는 pattern resolver를 분리하고, exact GetObject와 pattern ListBucket+prefix 최소 권한을 en/ko 문서에 명시했다. | 해결 |
| R-07 | POM 생성 task만 확인하면 실제 publication contract를 놓칠 수 있다. | `--no-configuration-cache --no-build-cache --no-daemon`, `test -f`, `validate_poms.rb`, runtime S3 negative grep와 검증 산출물 저장을 요구한다. | 해결 |
| R-08 | `tee`가 Gradle 실패를 가리거나 Floci fallback이 첫 실패 산출물을 덮을 수 있다. | `set -euo pipefail`, Floci stdout·XML·classification 보존, capability-gap일 때만 LocalStack을 실행하는 wrapper를 추가했다. | 해결 |
| R-09 | full module test가 emulator를 다시 수집하면 bounded Floci entrypoint를 우회할 수 있다. | full module 명령에 `-PskipAwsEmulatorTests=true`를 추가해 Floci는 단일 wrapper에서만 실행한다. XML 부재도 `capability-gap=unknown`으로 기록한다. | 해결 |

## Step 3-R 계약 점검

- 의존 순서: parser → protocol/pattern → auto-configuration/registration → Floci → 문서 → 통합 검증 순서가 RED → GREEN으로 고정돼 있다.
- 실패 격리: parser 오류는 `IllegalArgumentException`, listing·matcher·transport 오류는 cause를 보존한 `IOException`, partial result·silent fallback·empty fallback은 금지된다.
- 권한 경계: bucket discovery, cross-bucket, root listing, `ListAllMyBuckets`, retry, cache, 새 executor는 범위 밖이다.
- lifecycle 경계: resolver는 client와 stream을 소유하지 않으며 Spring context가 관리하는 client의 종료만 context lifecycle에 맡긴다.
- API/호환성: 기존 `S3Resource`와 `S3ObjectLocation` public contract는 유지하고 새 type만 추가하며, `@Primary`와 dependency/BOM/settings/CI mutation은 추가하지 않는다.
- 검증 경계: Floci가 먼저이며 capability gap일 때만 정확한 오류와 첫 실패 기준 데이터를 보존한 뒤 LocalStack을 fallback한다.
- 문서 경계: README와 en/ko manual에 동일한 exact/pattern/unsupported/IAM/lifecycle 계약을 기록하고 public KDoc은 한국어로 작성한다.

## SPW writer gate

| 항목 | 결과 | 증거 |
| --- | --- | --- |
| SPW-01 — audience, purpose, evidence | PASS | 구현 agent용 Type A 계획, Issue #463/Epic #500, 승인 spec, repository hazards, CI/Nightly와 publication source를 명시했다. |
| SPW-02 — artifact contract | PASS | task dependency, exact files, RED/GREEN commands, acceptance traceability, lifecycle/concurrency, rollback/rerun, approval·merge boundary를 포함한다. |
| SPW-03 — Korean technical register | PASS | 계획과 리뷰는 한국어 prose를 사용하고 code/API/command/token은 보존했다. `audit-korean-terms.mjs` 결과는 findings 0이다. |
| SPW-04 — meaning and traceability | PASS | 승인 spec과 repository source/workflow/hazard 근거를 대조했고 six-lane finding을 disposition table에 연결했다. |
| SPW-05 — read-back and DoD | PASS | 최종 Markdown을 다시 읽어 heading/table/list/code fence를 확인했고 placeholder scan과 `git diff --check`를 통과시킨 뒤 본 리뷰에 결과를 기록한다. |

## 최종 판정

`P0=0`, `P1=0`으로 구현계획 3-R 게이트를 통과한다. P2는 all-page 비용/heap이라는 승인된 잔여 위험 1건만 남겼고, constrained-heap·동시성·IAM prefix·10분 CI 예산 검증을 실행 단계의 필수 증거로 고정했다. 계획과 본 리뷰를 별도 Lore commit으로 저장할 수 있다.

구현 코드는 아직 시작하지 않았다. 다음 단계는 계획 승인 후 RED 테스트부터 실행하는 것이며, 구현·CI·PR·merge·release는 이 리뷰의 완료 주장에 포함하지 않는다.
