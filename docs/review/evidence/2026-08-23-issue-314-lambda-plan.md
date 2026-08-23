# 이슈 #314 Lambda 구현계획 독립 검토 증거

## 검토 범위와 기준

- 대상 계획: `docs/superpowers/plans/2026-08-23-issue-314-lambda-plan.md`
- 대상 설계: `docs/superpowers/specs/2026-08-23-issue-314-lambda-design.md`
- 기준 브랜치: `origin/develop` / `502bee2ea7e864fd8a7ed0b7e923961843a7bf30`
- 검토일: 2026-08-23
- 검토 방식: Performance, Stability, Security, Operator/Ops, Developer/API, User/caller의 6개 관점을 계획의 파일 지도·task 순서·명령·acceptance와 대조하고 main-session으로 통합했다.
- 판정 규칙: P0/P1은 구현 전에 0이어야 한다. P2/P3는 계획 task에 반영하거나 후속 보류 근거를 남긴다.

초기 spec-review lane의 bounded timeout 기록을 이어받아 plan review도 각 관점을 별도로 판정했다. 현재 native follow-up lane은 90초 bounded window에서 아직 결과를 반환하지 않았으므로 성공으로 간주하지 않고 main-session substitute evidence를 사용한다. 구현 전에 lane 결과를 다시 기다리지 않는다.

## 통합 검토 결과

| Lens | 상태 | P0/P1 | 근거 | 조치 |
|---|---|---:|---|---|
| Performance | PASS (main substitute) | 0 | Task 3에 bytes/result copy, UTF-8/base64 변환, 4 MiB payload 경계 test를 명시했다. Task 12는 targeted test와 full build를 실행하고 새 retry/polling loop를 만들지 않는다. | 별도 benchmark는 이슈의 thin facade 범위를 넘으므로 후속 최적화로 보류한다. copy 횟수와 content equality는 필수 증거다. |
| Stability | PASS (main substitute) | 0 | Task 5는 bounded async client scope와 success/exception/cancellation close를 검증하고, Task 6은 cancel-before-response/response-after-cancel/exactly-once completion을 검증한다. Task 8은 Kotlin `useSafe`와 suspend cancellation을 검증한다. | async future는 scope 안에서 await한다는 설계 계약을 유지한다. 외부 timeout/deadline은 caller-owned로 문서화된 상태다. |
| Security | PASS (main substitute) | 0 | Task 3의 hostile JSON/no unsafe typing, caller mapper, compileOnly 경계와 Task 9·10의 credential/function caller ownership·민감 payload/log 비기록을 확인했다. deployment/IAM mutation은 계획에서 금지된다. | 별도 security dependency 추가 없이 negative test와 운영 문서로 닫는다. |
| Operator/Ops | PASS (main substitute) | 0 | Task 1 capability probe, Task 9 client 생성 전 smoke skip와 exact XML/logger reason, Task 10 manual N/A/no retry, Task 12 `UNVERIFIED` evidence가 연결되어 있다. | smoke가 없을 때 green 통합으로 과장하지 않고 N/A/unsupported를 별도 evidence로 남긴다. |
| Developer/API | PASS (main review) | 0 | Task 2 aliases/compileOnly, Task 3~8의 실제 FQCN·파일·SDK-specific type 분리, Task 9 consumer compile, Task 13 base/head/PR metadata를 순차 고정했다. Task 2 fixture source를 Task 9로 늦춰 미생성 API에 대한 조기 compile failure도 제거했다. | 구현자는 계획의 파일 소유권과 SDK별 codec/result 대칭을 그대로 따른다. |
| User/caller | PASS (main substitute) | 0 | Task 10에 Java/Kotlin runtime dependency와 `withLambdaClient`/typed 호출 예제, FunctionError result, raw escape hatch, no retry/deployment, sensitive logging 주의를 명시했다. Task 9는 function/credential가 없으면 client 생성 전에 skip한다. | EN/KO manual과 module README의 heading/anchor/API link parity를 writer gate에서 확인한다. |

## 계획에서 확인한 수정 사항

1. Task 2의 consumer fixture source를 API 구현 이후인 Task 9로 이동해 계획 순서를 dependency → implementation → compile proof로 정정했다.
2. Task 3에 4 MiB payload 경계 test를 추가해 copy allocation 계약을 검증 가능하게 했다.
3. Task 10에 `withLambdaClient`와 typed Jackson 호출의 실제 Kotlin 예제를 추가하고 runtime service dependency 문장을 요구했다.
4. Task 1/11의 worktree-relative 경로를 실제 `/Users/debop/work/bluetape4k` sibling 위치에 맞췄다.
5. Java `LambdaPayloadCodec`를 `fun interface`가 아닌 두 abstract method를 허용하는 `interface`로 고정했다.

## Spec-to-plan trace

| 설계 계약 | 계획 task | 검증 증거 |
|---|---|---|
| aliases·compileOnly·consumer runtime | 2, 9 | Gradle dependency와 두 fixture compile |
| bytes/string/Jackson·copy/null/empty | 3, 7, 10 | 두 module codec test와 README/manual example |
| function/ARN·qualifier·invocation/log invariant | 4, 7 | Java/Kotlin request support test |
| raw response·FunctionError·error payload·log tail | 3, 6, 7, 8 | result/extension test |
| Java sync/async/coroutine cancellation | 5, 6 | lifecycle와 future race test |
| Kotlin native suspend/cancellation | 8 | `coEvery`/`coVerify`와 `useSafe` test |
| emulator boundary·no deployment | 1, 9, 12 | capability probe, exact N/A/XML, `UNVERIFIED` table |
| bilingual docs·manual·CHANGELOG | 10, 11 | Korean audit, manual contract, wiki search |
| PR exact head·merge separation | 13 | live issue/PR/checks fresh-read gate |

## Writer/plan gate

| Gate | 상태 | 근거 |
|---|---|---|
| 계획 header·architecture·tech stack | PASS | 문서 1~9행 |
| 파일·책임 지도 | PASS | 문서 13~20행 |
| TDD order | PASS | 각 구현 task의 실패 test → 실패 확인 → 최소 구현 → PASS |
| placeholder/type consistency | PASS | 계획 자체 점검과 source FQCN map |
| 6-lane coverage | PASS | 6개 lens 각각 상태·P0/P1·조치 기록 |
| P0/P1 closure | PASS | 최신 통합 결과 P0=0, P1=0 |
| Korean technical prose | PASS | `audit-korean-terms.mjs` findings=0 |
| whitespace integrity | PASS | `git diff --check` 통과 |

## 남은 검증 공백

- 실제 AWS function, IAM policy, production latency/fidelity는 caller credential와 deployed function이 없으므로 `UNVERIFIED`다.
- Floci/LocalStack Lambda capability가 없는 경우 smoke는 N/A이며 unit, consumer compile, build, docs evidence가 필수 DoD다.
- 별도 benchmark와 Spring Boot/Ktor facade는 Issue #314 범위 밖이다.

통합 판정: `PASS` (`P0=0`, `P1=0`). 계획은 사용자 승인 후에만 Task 1부터 실행한다. 승인 전에는 코드·dependency·PR mutation을 수행하지 않는다.
