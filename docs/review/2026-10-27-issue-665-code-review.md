# Issue #665 구현 최종 code review

## 검토 범위와 판정

- 대상 저장소: `bluetape4k/bluetape4k-aws`
- 구현 worktree: `.worktrees/feat-issue-665-aws-request-builders`
- branch: `feat/issue-665-aws-request-builders`
- base: `81f8e3e1a3c85f1850f4a9533da430bbb06c14c9`
- 현재 검토 기준 HEAD: `ec49f44baa7809f81167294cb6e4257b3a471719`
- 변경 범위: 8개 AWS Request support와 대응 테스트, `aws-java` README/CHANGELOG,
  설계·계획·review·lesson 문서
- 추적 이슈: [#665](https://github.com/bluetape4k/bluetape4k-aws/issues/665)

이번 문서는 `ec49f44b`에서 실행한 자동 검증과 독립 review lane의 결과를 분리해
기록한다. `fooRequest { }`는 SDK builder 필드만 받고, 필수 정보·helper-owned
invariant는 `fooRequestOf(...)`가 담당한다.

## 독립 review lane 상태

| Lane | 기준 HEAD | 결과 | P0/P1/P2/P3 | 비고 |
|---|---|---|---:|---|
| `code-reviewer` | `ec49f44b` | `REQUEST CHANGES` | `0/0/1/0` | 구현은 PASS, review artifact와 plan이 이전 HEAD를 가리킨다는 P2 |
| `architect` | `ec49f44b` | `WATCH` | `0/0/2/0` | 코드 blocker 없음, evidence/review 문서 provenance 갱신 필요 |
| developer/API plan review | `902f174c` | `PASS` | `0/0/0/0` | 함수 목록·ABI 명령·계획 수용 기준 확인 |
| main-session synthesis | `ec49f44b` | `PENDING` | — | 이 문서와 계획을 커밋한 뒤 새 exact HEAD에서 두 final lane을 재실행한다 |

이전 review의 SSM·Secrets `@PublishedApi` ABI 누출, direct construction 설계 불일치,
KDoc 부족, EOF 문제는 `6d86903c`에서 해소했다. `ec49f44b`에서는 callback이
필수 `type`을 제거하거나 SecretString/Binary를 동시에 설정하는 새 회귀를 추가로
검증하고, S3 Tables builder-only 12개를 `inline`으로 통일했다.

## 자동 검증 증거

| 영역 | 결과 | 증거 |
|---|---|---|
| `aws-java` 전체 테스트 | PASS — 592 tests, 15 skipped | `.bluetape/evidence/issue-665/module-test.log` |
| module build | PASS | `.bluetape/evidence/issue-665/build.log` |
| consumer fixture compile | PASS | `.bluetape/evidence/issue-665/consumer-fixture.log` |
| Detekt | PASS — findings 0 | `.bluetape/evidence/issue-665/detekt.log` |
| base/head full public `javap -public -s` | PASS — removed 0, added 49, unexpected 0 | `.bluetape/evidence/issue-665/abi-javap-summary.log` |
| Korean terminology·diff check | PASS — findings 0 | `.bluetape/evidence/issue-665/diff-check.log` |
| `compatibilityCheck` | `N/A (repository task failure)` | `.bluetape/evidence/issue-665/compatibility-check.log` |

전체 테스트의 첫 `--rerun-tasks` 실행은 Testcontainers가 Docker 환경을 일시적으로
찾지 못해 worker 오류를 연쇄시켰다. `colima status`, `docker context show`, `docker info`
로 Colima/Docker 상태를 확인한 뒤 동일 HEAD에서 재실행했고 592개 테스트가 성공했다.
이는 제품 코드 실패가 아니라 실행 환경의 일시적 장애로 분리한다.

`compatibilityCheck`는 AWS API assertion 전에 기존 `compileKinesisDryRunLegacyStubs`에서
`Build_gradle.getAwsKotlinProductionJar()` null 오류로 중단되고 configuration-cache
script-reference 경고 8건을 남겼다. 이 공통 task 실패는 이번 Request builder assertion으로
분류하지 않지만, 별도 compatibility gate가 완주하지 못했다는 검증 공백은 남긴다.

## 설계·API·보안 경계 확인

- 8개 support 파일에서 서로 다른 AWS SDK `Request` 타입마다 builder-only 진입점 하나를
  추가했고, S3 Tables를 포함한 49개 함수의 public ABI 추가가 source allowlist와 일치한다.
- 기존 `RequestOf`의 required scalar, overload, callback precedence, final validation을
  유지했다. 기존 builder block이 없던 함수에는 trailing block overload를 추가하지 않았다.
- SSM `PutParameterRequest`는 callback 후 `name`, `value`, `type`, `description`을
  검증한다. Secrets Manager create/put은 callback 후 `secretString`과
  `secretBinary`의 상호배타 조건을 검증한다.
- Bedrock은 `modelId`, `messages`, non-null `inferenceConfig`를 helper가 소유하고,
  나머지 SDK 필드는 callback이 설정하는 기존 precedence를 유지한다.
- raw secret-bearing builder에는 평문 보유 가능성과 `AwsSecretValue` 경계를 KDoc과
  README에 기록했다. raw builder-only 함수에는 helper validation을 강제하지 않는다.
- 네트워크 호출, client lifecycle, coroutine cancellation, emulator 설정, dependency
  변경은 추가하지 않았다.

## 결론과 다음 gate

- production/API/security: 현재 fresh review에서 P0/P1 없음.
- 자동 검증: `ec49f44b`에서 PASS.
- 남은 gate: 이 문서·계획·lesson 변경을 커밋한 뒤 새 exact HEAD에서
  `code-reviewer`와 `architect`를 다시 실행해 review provenance를 확정한다.
- `compatibilityCheck`: repository infrastructure failure로 PENDING.
- PR 생성, push, hosted CI dispatch, merge, publish, branch deletion은 수행하지 않았다.

현재 권고는 **PENDING**이며, independent final lane의 새 결과와 compatibility gate
상태를 확인하기 전에는 merge-ready로 간주하지 않는다.

## Writer gate

- SPW-01: PASS — 목적, 독자, 이슈, base와 exact implementation HEAD를 기록했다.
- SPW-02: PASS — independent lane, 자동 검증, 환경 실패, compatibility 공백을 분리했다.
- SPW-03: PASS — 한국어 prose와 API·명령·식별자를 구분했다.
- SPW-04: PASS — 설계 계약을 구현·테스트·ABI 증거에 매핑했다.
- SPW-05: PASS — Markdown read-back, terminology audit, `git diff --check`를 통과했다.
