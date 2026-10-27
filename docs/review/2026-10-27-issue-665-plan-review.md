# Issue #665 계획 통합 리뷰

## 검토 범위와 상태

- 대상 저장소: `bluetape4k/bluetape4k-aws`
- worktree: `.worktrees/feat-issue-665-aws-request-builders`
- branch: `feat/issue-665-aws-request-builders`
- 검토 기준 HEAD: `902f174c31381d337c10fa68af715c4a8acda3ad`
- 설계: `docs/superpowers/specs/2026-10-27-aws-request-builder-design.md`
- 계획: `docs/superpowers/plans/2026-10-27-aws-request-builder-plan.md`
- 이슈: [#665](https://github.com/bluetape4k/bluetape4k-aws/issues/665)
- 구현 상태: 시작하지 않음. RED 테스트와 production source 변경은 이 리뷰와 수정된 설계·계획의 사용자 승인 뒤에만 수행한다.

## 근거 원장

| 근거 | 확인 내용 |
|---|---|
| `SsmRequestSupport.kt`, `SecretsManagerRequestSupport.kt` | required-info helper, `AwsSecretValue`, callback 순서와 동일 SDK Request 타입 확인 |
| `SfnRequestSupport.kt`, `LambdaRequestSupport.kt` | final validation과 Step Functions `{}` normalization rebuild 확인 |
| `BedrockRuntimeRequestSupport.kt` | `modelId`/`messages`/non-null `inferenceConfig`의 helper-owned precedence 확인 |
| `EventBridgeRequestSupport.kt` | `eventPattern` 또는 `scheduleExpression` 하나 이상 필수이며 둘 다 허용되는 기존 동작 확인 |
| `SchedulerRequestSupport.kt`, `S3TablesRequestSupport.kt` | page/selector 검증과 S3 Tables post-build validation 순서 확인 |
| `aws-java/README.md`, `aws-java/README.ko.md`, `CHANGELOG.md` | 공개 사용법과 미출시 변경 기록 범위 확인 |
| `build.gradle.kts`, `.github/workflows/ci.yml` | `compileAwsJavaServiceConsumerFixture`, `compatibilityCheck`, hosted compatibility path filter 확인 |

## 독립 관점 결과

| 관점 | 판정 | 근거 및 처분 |
|---|---|---|
| Performance | PASS | 순수 SDK model construction이므로 benchmark는 N/A로 기록하고, `inline`, generic/reflection 부재, primary builder 1회/`build()` 1회 source inspection을 계획에 고정했다. Step Functions의 의도된 `{}` normalization rebuild는 예외로 명시했다. |
| Stability | PASS | RED를 서비스 slice별로 분리해 미구현 심볼이 다음 slice GREEN을 막지 않게 했고, S3 Tables post-build 순서와 receiver block 정확히 1회 회귀 테스트를 고정했다. |
| Security | PASS | callback 무효값의 build 후 검증, Bedrock helper-owned 값의 덮어쓰기, secret sentinel 비노출, secret-bearing raw builder trust boundary를 계획에 반영했다. 네트워크·인증·권한 경계 추가는 N/A다. |
| Operator/Ops | PASS | raw log와 committed review를 분리하고, exact HEAD/명령/결과/skipped/N/A/P0/P1 처분을 기록한다. `compatibilityCheck`와 local consumer fixture를 실행하며 8 facade의 base/head `javap -public -s` 비교를 항상 수행한다. hosted compatibility skip은 별도 N/A다. |
| Developer/API | PASS | distinct AWS SDK Request 타입별 builder-only 진입점, SSM specialization, EventBridge at-least-one 계약, overload/ABI 경계, TDD 순서와 정확한 파일 목록이 일치한다. |
| User/caller | PASS | `fooRequest { }`와 required-info `fooRequestOf(...)` 경계, Bedrock 예외, SSM `PutParameterRequest` specialization을 KDoc과 한·영 README paired example에 반영하도록 했다. |

## 통합 판정

- P0: 0
- P1: 0
- P2/P3: 0
- unresolved technical finding: 없음
- 구현 전 사용자 승인: `PENDING` — 기존 설계 승인 이후 source drift를 반영한 설계·계획 정정에 대한 재승인이 필요하다.
- PR/push/hosted CI/merge/publish/branch deletion: `PENDING` — 별도 권한과 최신 구현 HEAD 검토 전에는 수행하지 않는다.

## Writer gate

- SPW-01: PASS — artifact 목적, 독자, 이슈/설계/계획/소스 경로, exact HEAD와 미확정 승인 상태를 고정했다.
- SPW-02: PASS — 관점별 판정, 근거, 처분, 통합 verdict, traceability와 다음 gate를 포함했다.
- SPW-03: PASS — 한국어 기술 문체, API/명령/식별자 보존, 용어 일관성을 확인했다.
- SPW-04: PASS — source-to-claim 및 spec-to-plan 매핑을 재검토하고 source drift를 설계·계획에 반영했다.
- SPW-05: PASS — Markdown 표와 목록을 read-back했고, terminology audit 및 `git diff --check`를 통과했다.

## 검증 명령

```text
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs --json \
  docs/superpowers/specs/2026-10-27-aws-request-builder-design.md \
  docs/superpowers/plans/2026-10-27-aws-request-builder-plan.md
git diff --check
```

두 명령은 현재 HEAD에서 성공했고 terminology findings는 비어 있다. 구현·테스트·ABI·Detekt 결과는 아직 존재하지 않으며, 구현 후 별도 code-review artifact에 기록한다.
