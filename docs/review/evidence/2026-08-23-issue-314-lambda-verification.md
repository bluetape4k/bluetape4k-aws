# 이슈 #314 Lambda 구현 검증 증거

## 검증 범위

- 대상: `feat/issue-314-lambda`의 Java SDK v2·AWS SDK for Kotlin Lambda helper
- 기준: `origin/develop` (`502bee2ea7e864fd8a7ed0b7e923961843a7bf30`)
- 검증 기준 커밋: `068e165` 및 해당 커밋 이후의 구현·문서 worktree 변경
- 범위 제외: 함수 배포, IAM mutation, retry/polling, Spring Boot/Ktor facade, merge

## 계획·체크리스트 상태

| 항목 | 상태 | 증거 |
|---|---|---|
| SDK alias·compileOnly·consumer 경계 | PASS | `libs.aws2.lambda`, `libs.aws.kotlin.lambda`, 두 모듈 compileOnly와 fixture compile |
| Java sync/async/coroutine API | PASS | codec·request·lifecycle·extension 테스트 29건 통과 |
| Kotlin native suspend API | PASS | codec·request·lifecycle·extension 테스트 24건 통과 |
| cancellation·FunctionError·payload/log 계약 | PASS | targeted 및 전체 모듈 테스트, raw response/copy/null-empty 테스트 |
| public 문서·manual·CHANGELOG | PASS | EN/KO 문서 반영, manual contract·manifest·inventory 통과 |
| emulator smoke 경계 | N/A | 필수 function/region 입력 부재로 client 생성 전 skip; Floci Lambda 호출 미지원 |
| 실제 AWS function/IAM fidelity | UNVERIFIED | 실행 가능한 함수·권한 입력이 없어 live 호출을 수행하지 않음 |

## 실행 결과

모든 Gradle 명령은 공유 emulator 자원을 피하기 위해 필요한 경우 순차 실행했으며,
기존 publication task의 configuration-cache 호환성 문제 때문에 검증 명령에는
`--no-configuration-cache`를 사용했다.

```text
./gradlew :bluetape4k-aws-java:test --tests 'io.bluetape4k.aws.lambda.*' --no-daemon
29 passing

./gradlew :bluetape4k-aws-kotlin:test --tests 'io.bluetape4k.aws.kotlin.lambda.*' --no-daemon
24 passing

./gradlew :bluetape4k-aws-java:detekt :bluetape4k-aws-kotlin:detekt --no-daemon
BUILD SUCCESSFUL

./gradlew compileAwsJavaServiceConsumerFixture compileAwsKotlinServiceConsumerFixture --no-daemon
BUILD SUCCESSFUL

./gradlew --no-configuration-cache verifyAwsConsumerFixturePublication --no-daemon
BUILD SUCCESSFUL

./gradlew :bluetape4k-aws-java:test --no-configuration-cache --no-daemon
450 passing, 15 pending; BUILD SUCCESSFUL

./gradlew :bluetape4k-aws-kotlin:test --no-configuration-cache --no-daemon
630 passing, 13 pending; BUILD SUCCESSFUL

./gradlew build -x test --parallel --no-configuration-cache --no-daemon
BUILD SUCCESSFUL; 52 actionable tasks

./gradlew build --no-configuration-cache --no-daemon
BUILD SUCCESSFUL; 94 actionable tasks
```

기본 configuration-cache 경로의 `verifyAwsConsumerFixturePublication`은 기존
Gradle `withXml` task에서 `ConfigurationContainer.detachedConfiguration` delegate가
없는 오류로 실패했다. 동일 publication gate를 `--no-configuration-cache`로 재실행해
통과했으며, 이 호환성 문제는 Lambda 코드 실패가 아닌 기존 검증 인프라 제약으로
남긴다.

## 문서·smoke 검증

```text
ruby scripts/manual/manual_contract_test.rb
9 runs, 44 assertions, 0 failures

ruby scripts/manual/export_manifest.rb docs/manual/manifest.yaml docs/manual/generated/manifest.json --check
Manual manifest snapshot is current.

./gradlew exportManualModuleInventory --no-configuration-cache --no-daemon
BUILD SUCCESSFUL

git diff --check
PASS
```

새 Lambda readiness 문서와 Java/Kotlin manual은 terminology audit에서 findings=0을
기록했다. 기존 전체 README/CHANGELOG audit의 13개 `snapshot-loanword` finding은
`origin/develop`에도 동일하게 존재하며, 현재 변경으로 추가된 rule/match는 0개다.

입력 없는 opt-in smoke는 다음과 같이 두 모듈 모두 client 생성 전에 종료됐다.

```text
lambda-smoke: SKIP before client creation; missing=LAMBDA_SMOKE_FUNCTION_NAME,LAMBDA_SMOKE_REGION
BUILD SUCCESSFUL
```

실제 Lambda 호출은 함수·권한·emulator capability가 없으므로 `N/A`/`UNVERIFIED`로
분리했다. smoke green을 live AWS fidelity 증거로 확대하지 않는다.

## 외부 근거 보존

공식 AWS Lambda Invoke 자료의 한국어 결정 요약은
`bluetape4k-wiki/research/2026-08-23-aws-lambda-invoke-helper.md`에 보존했다.
`git diff --check`, `gno update`, `gno embed --collection bluetape4k-wiki`,
`gno search 'AWS Lambda Invoke helper FunctionError' -c bluetape4k-wiki --limit 3`를
실행했고 검색 결과에 해당 note가 나타났다.

## 판정

- 구현·문서·로컬 검증 DoD: `PASS`
- 실제 AWS Lambda/IAM fidelity: `UNVERIFIED`
- PR CI·review·merge gate: 아직 수행하지 않음
- 최종 상태: `PENDING` (PR 생성 후 exact-head·CI·review fresh-read 필요)
