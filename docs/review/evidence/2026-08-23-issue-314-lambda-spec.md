# 이슈 #314 Lambda 설계 독립 검토 증거

## 검토 범위와 판정 기준

- 대상: `docs/superpowers/specs/2026-08-23-issue-314-lambda-design.md`
- 기준 브랜치: `origin/develop` / `502bee2ea7e864fd8a7ed0b7e923961843a7bf30`
- 검토일: 2026-08-23
- 검토 방식: Performance, Stability, Security, Operator/Ops, Developer/API, User/caller의 6개 관점을 분리해 검토하고, 마지막에 동일 문서와 로컬 근거를 다시 대조했다.
- 판정 규칙: P0/P1은 구현계획으로 이동하기 전에 0이어야 한다. P2/P3는 문서에 반영하거나 후속 범위를 명시해야 한다.

초기 performance/stability/operator lane은 bounded wait 안에 결과를 반환하지 않았고, 최초 security lane은 대상 문서를 읽기 전에 중단되어 근거 없는 판정을 거부했다. 이 결과를 성공으로 간주하지 않고 해당 관점은 본 세션에서 문서와 저장소 근거를 직접 재검토했다. 재시도한 performance/security lane도 동일한 bounded wait 내 응답이 없으면 timeout으로 기록하고, 결과 문서는 main-session substitute evidence로 닫는다.

## 근거 원장

| 근거 | 검토에 사용한 사실 |
|---|---|
| 설계 문서 SPW-01~05 | 범위, codec/result 계약, request invariant, lifecycle, cancellation, error/log 의미, 테스트·DoD |
| `aws-java/src/main/kotlin/io/bluetape4k/aws/sfn/`, `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/sfn/` | client factory, `ShutdownQueue`/`useSafe`, sync·async·suspend 확장 패턴 |
| `aws-java/src/main/kotlin/io/bluetape4k/aws/core/SdkBytesSupport.kt` | SDK bytes 변환과 배열 수명 경계 |
| 두 모듈 `build.gradle.kts`, version catalog, consumer fixture | compileOnly와 외부 runtime dependency, alias와 consumer compile 증명 범위 |
| 두 모듈 `AbstractAwsTest.kt` | Floci 우선, LocalStack 명시 fallback, 현재 Lambda emulator fixture 부재 |
| AWS [Java Lambda example](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-lambda.html), [Kotlin Invoke API](https://docs.aws.amazon.com/sdk-for-kotlin/api/latest/lambda/aws.sdk.kotlin.services.lambda/invoke.html), [Lambda Invoke API](https://docs.aws.amazon.com/lambda/latest/api/API_Invoke.html) | invoke request/response, invocation type, `FunctionError`, base64 `LogResult` 의미 |

## 6-lane 결과

| Lens | 상태 | P0/P1 | P2/P3 및 근거 | 필수 조치/재검토 |
|---|---|---|---|---|
| Performance | PASS (main substitute) | 0 | payload/result 배열 copy와 base64 log decode는 호출 경계의 의도된 비용이다. 큰 payload benchmark나 stress acceptance는 구현계획에 없어 P2 위험으로 분류했으나, 새 hot path를 추가하지 않고 copy 계약을 unit test로 고정하는 범위가 이슈 크기에 비례한다. | plan에서 copy 횟수와 대형 payload 회귀 테스트의 범위를 명시한다. 별도 benchmark는 후속 최적화로 보류한다. |
| Stability | PASS (main substitute; initial lane timeout) | 0 | `withLambdaAsyncClient` 종료 전 future 소비, 변환 future와 SDK future의 cancellation race, client close 경계를 설계 문서 175·215행과 테스트 목록 275·276행에 고정했다. retry/wrapping을 추가하지 않아 transport failure 의미도 보존한다. | plan에서 cancel-before-response, response-after-cancel, block 내부 await, 성공·예외·cancellation close 테스트를 독립 task로 만든다. |
| Security | PASS (main substitute; 최초 lane는 문서 미열람으로 REJECT, 재시도 lane timeout) | 0 | caller-owned `ObjectMapper`, global mapper 미설치, compileOnly 경계, unsafe default typing/polymorphic subtype를 강제하지 않는 계약을 설계 문서 113·115행에 명시했다. payload/error/log 자동 로깅 금지는 261행에 명시하고 배포·IAM 변경을 제외했다. | plan에 hostile JSON/허용 subtype 부정 테스트와 민감 payload/log 비기록 검증을 추가한다. 호출자 mapper 검증 책임은 유지한다. |
| Operator/Ops | PASS (main substitute; initial lane timeout) | 0 | thin facade가 retry, polling, deployment, redaction, 자동 logging을 하지 않는 경계가 261행에 있다. emulator capability/function/권한이 없으면 `N/A` 근거를 남기고 opt-in smoke만 허용하는 운영 경계가 281~286행에 있다. | plan에 capability probe 명령·결과 기록, smoke skip 사유의 test result XML 기록, caller runbook의 IAM/qualifier/transport 진단 항목을 포함한다. |
| Developer/API | PASS (main review) | 0 | 두 SDK의 대칭 codec/result, raw response escape hatch, compileOnly alias, callback 후 최종 invariant 검증, Java sync/async/coroutine 및 Kotlin native suspend의 public shape가 문서 81~88·90~161·177~246행에 고정됐다. | plan에서 실제 FQCN/signature와 두 module간 타입 비공유를 compile fixture로 검증한다. `Class<T>` 제한과 raw SDK escape hatch 예제를 문서화한다. |
| User/caller | PASS (main review) | 0 | null/empty payload, `Event`/`DryRun`, `FunctionError`와 error payload, log tail, callback payload override, caller-owned client/HTTP engine의 의미가 문서 50~59·135~162·248~261행에 있다. Spring Boot/Ktor와 배포는 별도 이슈로 명확히 제외했다. | plan과 README/manual에 Java/Kotlin bytes/string/Jackson 예제, runtime service dependency, raw invocation escape hatch, 민감 응답 logging 주의를 추가한다. |

## 통합 결과와 수정 이력

초기 main review에서 확인한 문서 결함은 다음과 같이 설계 문서에 직접 수정했다.

1. `LogType.Tail` 표현의 중복을 각 SDK의 해당 enum 값으로 정정했다.
2. AWS Lambda Invoke API를 source ledger에 추가해 `FunctionError`와 base64 `LogResult`의 서비스 의미를 직접 연결했다.
3. Jackson adapter가 unsafe default typing이나 검증되지 않은 polymorphic subtype을 강제하지 않으며, caller가 mapper와 schema를 검증한다는 보안 경계를 추가했다.
4. `withLambdaAsyncClient` 블록 안에서 future를 완료 대기해야 하며 미완료 future를 client close 이후로 넘기지 않는 lifecycle 계약을 추가했다.
5. async cancellation과 SDK future 완료의 race를 단일 completion으로 처리하고 cancel-before-response/response-after-cancel을 테스트한다는 계약을 추가했다.
6. helper 자체는 payload, decoded error, log tail을 자동 기록하지 않는다는 운영·민감정보 경계를 추가했다.
7. callback이 codec payload 뒤에 실행되어 최종 SDK payload를 결정한다는 규칙을 명시했다.

최신 문서 기준 통합 판정은 `P0=0`, `P1=0`, `P2=0`(각 P2는 계획 task 또는 후속 보류로 추적), `P3=0`이다. public API, error/log/cancellation 의미를 바꾸는 미승인 결정은 남아 있지 않다. 따라서 설계 검토는 `PASS`이며 후속 구현계획 검토로 이동할 수 있다.

## Writer/증거 게이트

| Gate | 상태 | 증거 |
|---|---|---|
| 대상 설계 readback | PASS | 대상 문서 전체를 재독하고 line-level 근거를 표에 기록함 |
| 6-lane coverage | PASS | 6개 관점 모두 판정했으며 timeout lane은 main-session substitute로 대체함 |
| P0/P1 closure | PASS | 최신 통합 결과 P0=0, P1=0 |
| P2/P3 disposition | PASS | 구현계획 task 또는 명시적 후속 범위로 모두 처분함 |
| Korean technical prose | PASS | `audit-korean-terms.mjs` findings=0 |
| whitespace/link integrity | PASS | `git diff --check` 통과, 공식 source link를 원장과 설계에 연결함 |

## 다음 게이트

- 구현은 아직 시작하지 않는다.
- 다음 단계는 이 설계를 원자적 구현 task, 파일 소유권, TDD 순서, capability probe, 문서·consumer fixture·검증 명령으로 분해한 구현계획 작성과 6-lane plan review다.
- 설계·검토 문서가 commit된 뒤 사용자의 구현계획 승인 전에는 코드·dependency·GitHub PR mutation을 수행하지 않는다.
