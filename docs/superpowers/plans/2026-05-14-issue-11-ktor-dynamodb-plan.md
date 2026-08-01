# 이슈 #11 Ktor DynamoDB 계획

작성일: 2026-05-14
명세: `docs/superpowers/specs/2026-05-14-issue-11-ktor-dynamodb-design.md`
이슈: https://github.com/bluetape4k/bluetape4k-aws/issues/11
관련 migration 이슈: https://github.com/bluetape4k/bluetape4k-aws/issues/85

## 분류

유형 A - Full Design.

판단 근거:

- `feat:` 이슈.
- 새 Ktor server plugin.
- 새 DynamoDB repository/runtime layer.
- 여러 파일의 build, 문서, test, lesson 갱신.

## 실행 규칙

- `.worktrees/issue-11-ktor-dynamodb`에서 작업한다.
- `bluetape4k-aws`의 `:aws-kotlin`과 공식 AWS SDK for Kotlin DynamoDB를 주요 DynamoDB surface로 사용한다.
- Java SDK v2 Enhanced Client를 기본 Ktor 구현으로 사용하지 않는다.
- 기존 `:aws-kotlin` client factory, table utility, mapper, DynamoDB helper를 `aws-ktor`에 중복하지 않는다.
- 이 이슈에서 기존 Ktor S3/SQS/SigV4 구현을 migration하지 않는다. 해당 작업은 #85에서 추적한다.
- public KDoc과 GitHub/CHANGELOG artifact는 영문으로 유지한다.
- `aws-ktor/README.md`와 `aws-ktor/README.ko.md`를 함께 갱신한다.
- PR 공개 전에 lesson을 추가한다.

## 작업 목록

### T0 - 설계 검토

- [x] 단계 2-R 명세 검토 실행.
- [x] 필수 Claude advisor 검토 실행.
- [x] 수용한 명세 발견 사항 반영.
- [x] 단계 3-R 계획 검토 실행.
- [x] 필수 Claude advisor 검토 실행.
- [x] 수용한 계획 발견 사항 반영.
- [x] 구현 전에 명세와 계획 commit.

### T1 - build 연결

- [x] `aws-ktor`에서 `project(":aws-kotlin")`을 `compileOnly`에서 `api`로 승격.
- [x] `aws-ktor`에 `compileOnly(libs.aws.kotlin.dynamodb)` 추가.
- [x] `aws-ktor`에 `testImplementation(libs.aws.kotlin.dynamodb)` 추가.
- [x] #85에서 migration 경로를 정의할 때까지 기존 S3/SQS/SigV4 code를 위해 `project(":aws")`를 `api`로 유지.
- [x] `:aws-kotlin`을 helper module dependency로 유지하고 public helper인 `dynamoDbClientOf`, `withDynamoDbClient`, `DynamoItemMapper`, table helper, batch helper 재사용.
- [x] wiring 전용 sanity check로 `:aws-ktor:compileKotlin` compile.

### T2 - `:aws-kotlin` mapper 추가

- [x] `:aws-kotlin`에 `DynamoItemReader<T>` 추가.
- [x] 단순 entity를 사용한 `DynamoItemReader<T>` 사용법 unit test 추가.
- [x] `:aws-kotlin:compileKotlin` 컴파일.
- [x] `:aws-kotlin:test` 실행.

### T3 - runtime과 plugin

- [x] `io.bluetape4k.aws.ktor.dynamodb` package 추가.
- [x] `DynamoDbKtorPluginConfig` 추가.
- [x] `DynamoDbKtorRuntime` 추가.
- [x] `DynamoDbKtorPlugin` 추가.
- [x] `Application.attributes`에 저장하는 `AttributeKey<DynamoDbKtorRuntime>`인 `DynamoDbKtorRuntimeKey` 추가.
- [x] `Application.dynamoDb()` 추가.
- [x] injected 및 plugin-owned AWS Kotlin SDK `DynamoDbClient` 지원.
- [x] plugin-owned client만 닫음.
- [x] 설정했을 때 등록한 table auto-creation을 실행하도록 `ApplicationStarted` hook 연결.
- [x] 제한된 timeout으로 plugin-owned client를 닫도록 `ApplicationStopping` hook 연결. 사용했다면 `runBlocking(Dispatchers.IO)` suspend-close bridge 문서화.

### T4 - table model과 repository

- [x] 선택형 auto-creation을 위한 명시적인 table definition model 추가.
- [x] table definition `data class`를 피함. function-backed table builder에는 serialization 계약이 필요하지 않음.
- [x] `:aws-kotlin`, AWS Kotlin SDK item map, 명시적 mapper를 사용하는 repository 계약 추가.
- [x] v1 `save`, `findById`, `deleteById`, `scan`, `query` 추가.
- [x] `count`, `batchGet`, 고급 update expression, schema 검증, named-client registry 연기.

### T5 - 테스트

- [x] config validation test 추가.
- [x] lifecycle/client ownership test 추가.
- [x] injected client를 닫지 않음을 입증하는 test 추가.
- [x] `autoCreateTables = true`가 startup에서 등록된 table을 생성함을 입증하는 test 추가.
- [x] 기존 table을 idempotent하게 건너뜀을 입증하는 test 추가.
- [x] `autoCreateTables = false`가 table을 변경하지 않음을 입증하는 test 추가.
- [x] save/find용 LocalStack DynamoDB integration test 추가.
- [x] scan 또는 query `Flow` test 추가.
- [x] 고정 sleep 대신 Awaitility/bounded polling 사용.
- [x] 기존 `aws-ktor` test package/tag convention 준수.

### T6 - 문서

- [x] `aws-ktor/README.md` 갱신.
- [x] `aws-ktor/README.ko.md` 갱신.
- [x] AWS Kotlin SDK dependency 요구 사항 언급.
- [x] `aws.sdk.kotlin:dynamodb` consumer dependency snippet 포함.
- [x] AWS Kotlin DynamoDB Mapper Developer Preview가 기본값이 아님을 언급.
- [x] 새 public API에 summary와 behavior/contract note를 포함한 영문 KDoc 추가.

### T7 - 검증

- [x] `git diff --check`
- [x] `./gradlew :aws-kotlin:detekt` - module task 사용 불가. 루트 `./gradlew detekt`가 `NO-SOURCE`를 반환함을 검증.
- [x] `./gradlew :aws-kotlin:test`
- [x] `./gradlew :aws-ktor:detekt` - module task 사용 불가. 루트 `./gradlew detekt`가 `NO-SOURCE`를 반환함을 검증.
- [x] `./gradlew :aws-ktor:compileKotlin :aws-ktor:compileTestKotlin`
- [x] `./gradlew :aws-ktor:test --tests 'io.bluetape4k.aws.ktor.dynamodb.*'`
- [x] `./gradlew :aws-ktor:test`
- [x] `./gradlew :aws:compileKotlin :aws-kotlin:compileKotlin :aws-spring-boot:compileKotlin :aws-ktor:compileKotlin :aws-kotlin:compileTestKotlin :aws-spring-boot:compileTestKotlin :aws-ktor:compileTestKotlin`
- [x] `./gradlew :aws:test :aws-kotlin:test :aws-spring-boot:test :aws-ktor:test`
- [x] AWS module이 `bluetape4k-jackson3`, `io.bluetape4k.jackson3`, `tools.jackson`을 사용함을 검증.
- [x] 단계 4 code review.
- [x] 필수 Claude code review advisor.

### T8 - 지식과 PR

- [x] `docs/lessons/2026-05-14-issue-11-ktor-dynamodb.md` 추가.
- [ ] Lore trailer와 함께 구현 commit.
- [ ] branch를 push.
- [ ] 영문 draft PR 생성.
- [ ] merge-readiness를 요청하면 PR 후 dual review / merge gate 실행.

## 인수 확인 목록

- [x] Ktor application에 `DynamoDbKtorPlugin` 설치 가능.
- [x] runtime이 AWS Kotlin SDK `DynamoDbClient` 제공.
- [x] repository 경로가 `:aws-kotlin`, 공식 AWS Kotlin SDK DynamoDB type, 명시적 mapper 사용.
- [x] 선택형 table auto-creation을 명시적으로 설정.
- [x] query/scan이 Kotlin `Flow` 제공.
- [x] test로 lifecycle, ownership, LocalStack CRUD 동작 입증.
- [x] README locale pair가 최신 상태.

## 단계 3-R 검토 기록

- Claude advisor 산출물:
  `.omx/artifacts/claude-issue-11-ktor-dynamodb-plan-20260514-201436.md`.
- P0/P1 수용 항목:
  - table auto-creation test 추가.
  - 명시적인 `ApplicationStarted` / `ApplicationStopping` lifecycle 작업 추가.
  - `:aws-kotlin` mapper 작업과 `aws-ktor` plugin/repository 작업 분리.
  - #85까지 `project(":aws")`를 `api`로 유지.
  - detekt, `:aws-kotlin:test`, 영문 KDoc 작업 추가.
- 기각 항목:
  - 없음.
- 수정 후 수렴 상태: P0 = 0, P1 = 0.

## 구현 검토 기록

- Claude advisor 산출물:
  `.omx/artifacts/claude-issue-11-ktor-dynamodb-code-review-20260514-203024.md`.
- 수용한 발견 사항:
  - `DynamoDbKtorRuntimeConfig`를 `data class`에서 일반 `class`로 변경.
  - 동시 DynamoDB table 생성의 `ResourceInUseException`을 idempotent auto-create race로 처리하고 준비 완료 대기.
  - LocalStack repository test에 `deleteById` coverage 추가.
  - plugin comment에 synchronous Ktor lifecycle suspend bridge 문서화.
- 후속 필수 검토:
  - `.omx/artifacts/ask-claude-code-review-issue-11-ktor-dynamodb-postfix-20260514-203547.md`
  - 수용한 P2 발견 사항: plugin-owned close timeout logging, 기존 table 준비 완료 대기, plugin-owned client close coverage 추가.
  - `.omx/artifacts/ask-claude-code-review-issue-11-ktor-dynamodb-final-jackson3-20260514-205134.md`
  - 수용한 P2 발견 사항: Dependabot Jackson 3 grouping, interruptible bounded client close, repository `save`/`put` 중복 정리.
  - `.omx/artifacts/ask-claude-code-review-issue-11-ktor-dynamodb-final-clean-20260514-205816.md`
  - 최종 판정: P0 = 0, P1 = 0, P2 = 0. 승인 / merge 준비 완료.
- 기각 항목:
  - 없음.
- 수정 후 수렴 상태: P0 = 0, P1 = 0, P2 = 0.
