# Issue #311 S3 Tables 관리 API helper 실행 계획

> 사용자 승인: 2026-08-23 `승인`. 이 문서는 구현·검증·PR까지의 실행 순서를 고정하며, merge는 별도 승인 게이트다.

## 목표와 구조

Java SDK v2와 AWS SDK for Kotlin의 S3 Tables 관리 API를 얇은 raw-SDK extension으로 제공한다.
`aws-java`는 sync/async/coroutine 층과 lifecycle factory를 대칭으로 두고, `aws-kotlin`은 native suspend와
명시적 client lifecycle을 사용한다. 두 모듈의 service SDK는 계속 `compileOnly`다.

## 기준선과 범위

- Worktree: `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat-issue-311-s3-tables`
- Branch: `feat/issue-311-s3-tables`
- Base: `develop` `502bee2ea7e864fd8a7ed0b7e923961843a7bf30`
- 변경 전 baseline: Java/Kotlin compile PASS, Java test `436 tests / 0 failures / 0 errors / 15 skipped`, Kotlin test `619 / 0 / 0 / 13 skipped`
- 실행 중 생성되는 `.bluetape` 상태는 transient runtime이며 수동 편집하지 않는다.
- 이 계획은 commit·push·PR 생성까지 포함하고 merge·release·branch 삭제는 포함하지 않는다.

## 파일 책임 맵

| 책임 | 파일 |
|---|---|
| catalog/BOM/consumer 경계 | `gradle/libs.versions.toml`, `build.gradle.kts`, `aws-java/build.gradle.kts`, `aws-kotlin/build.gradle.kts`, 두 consumer fixture |
| Java lifecycle/operation/request | `aws-java/src/main/kotlin/io/bluetape4k/aws/s3tables/**` |
| Kotlin lifecycle/operation/request | `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/s3tables/**` |
| Java/Kotlin 단위 테스트 | 각 모듈 `src/test/kotlin/**/s3tables/**` |
| 문서 | root/module README EN·KO, `docs/manual/{en,ko}/modules/*`, `CHANGELOG.md`; `WIP.md`는 2026-07-28에 갱신된 오래된 backlog라 이번 변경에서 부분 수정하지 않고 live GitHub와 이 계획/evidence를 기준으로 삼음 |
| evidence/review | `docs/review/2026-08-23-issue-311-s3-tables-*.md` |

## 실행 단계

### 0. 문서·리뷰 기준선

- [ ] 설계·계획 파일을 추가하고 Korean terminology audit, `git diff --check`, SHA를 기록한다.
- [ ] 보안·API·테스트·lifecycle·호환성·문서 관점의 독립 리뷰를 받아 design/plan review artifact에 통합한다.
- [ ] Lore commit으로 문서 기준선을 기록한다.

### 1. catalog와 compileOnly 경계 (RED → GREEN)

- [ ] consumer fixture에 raw `S3TablesClient` 참조를 먼저 추가해 alias 누락 RED를 확인한다.
- [ ] `aws2.s3tables`, `aws.kotlin.s3tables` alias, 양 모듈 compileOnly/test dependency를 추가한다.
- [ ] root consumer fixture classpath, BOM constraint, publication forbidden dependency를 추가한다.
- [ ] 정상 compile(`./gradlew compileAwsJavaServiceConsumerFixture compileAwsKotlinServiceConsumerFixture`),
  Java omission(`-PconsumerFixtureOmit=aws-java:s3tables`), Kotlin omission
  (`-PconsumerFixtureOmit=aws-kotlin:s3tables`)과
  `./gradlew verifyAwsConsumerFixturePublication`을 실행한다.

### 2. Java API

- [ ] request helper: bucket/namespace/table create·get·list·delete와 최종 callback invariant.
- [ ] `CreateTable.format=ICEBERG` 기본값·최종 검증, `GetTable` selector XOR, 한 페이지 목록 token/max/filter 계약을 테스트한다.
- [ ] sync extension과 async extension/`.await()` delegation을 검증한다. 테스트 matrix는
  `create/list/get/deleteTableBucket`, `create/list/get/deleteNamespace`,
  `create/list/get/deleteTable` 12개 operation을 Java sync와 coroutine에서 모두 확인하고,
  Java `CompletableFuture`는 같은 12개 request delegation 및 future completion을 확인한다.
- [ ] application-scoped `s3TablesClient*`, short-lived `withS3Tables*Client`; close ownership을 고정한다.
- [ ] MockK 기반 request mapping, async exception/cancellation, lifecycle 성공·예외·cancellation 테스트를 추가한다.

### 3. Kotlin API

- [ ] Kotlin request helper와 native suspend extension을 추가한다.
- [ ] `s3TablesClientOf`와 `withS3TablesClient`를 추가하고 `useSafe` lifecycle을 따른다.
- [ ] Java와 대칭으로 `format=ICEBERG` 최종 검증, `GetTable` selector XOR, 한 페이지 token/max/filter와 callback override, exception/cancellation, caller-owned HTTP engine 테스트를 추가한다.

### 4. 문서와 opt-in smoke

- [ ] root/module README EN·KO service matrix와 compile/runtime dependency 예제를 갱신한다.
- [ ] Java/Kotlin manual에 관리 API 범위, Iceberg/Athena/Glue/Redshift 경계, lifecycle을 갱신한다.
- [ ] CHANGELOG를 갱신한다. `WIP.md`는 오래된 backlog를 부분 수정하지 않고 제외 사실과 live GitHub 기준을 evidence에 기록한다.
- [ ] read-only와 mutating real AWS smoke를 분리한다. mutating 경로는 명시 account/region, 고유 prefix, 이번 실행이 만든 리소스만 역순 삭제하는 `finally`, timeout, sanitized evidence를 요구한다.
- [ ] mutating smoke는 첫 생성 전에 STS `GetCallerIdentity.account`와 `S3_TABLES_EXPECTED_ACCOUNT_ID`를 비교하고 불일치 시 fail-closed 한다. read-only는 `-Ps3TablesReadOnlySmoke` + `S3_TABLES_READ_ONLY_REGION` + `S3_TABLES_READ_ONLY_TABLE_BUCKET_ARN`, mutating은 `-Ps3TablesMutatingSmoke` + `S3_TABLES_EXPECTED_ACCOUNT_ID` + `S3_TABLES_MUTATING_REGION` + `S3_TABLES_MUTATING_PREFIX`를 계약으로 고정한다.
- [ ] smoke 입력이 없으면 client 생성 전에 skip하고, 실행 태그는
  `s3-tables-read-only-smoke`/`s3-tables-mutating-smoke`, Gradle opt-in은
  `-Ps3TablesReadOnlySmoke`/`-Ps3TablesMutatingSmoke`로 고정한다. 각 모듈의 기본 test task는
  두 태그를 제외하며, smoke skip 문구와 missing env 이름을 로그에 남긴다. emulator는 지원
  근거 없음을 evidence에 기록한다.

### 5. 검증·review·PR

- [ ] targeted tests → full module tests → detekt/build를 순서대로 실행한다.
- [ ] consumer fixture/publication, manual manifest/contract/inventory, `git diff --check`를 실행한다.
- [ ] final code review와 evidence 문서를 exact HEAD 기준으로 작성한다.
- [ ] 한국어 PR을 생성하고 CI/review/mergeability를 fresh-read한다. merge는 별도 승인 전까지 보류한다.

## 위험과 대응

| 위험 | 대응 |
|---|---|
| SDK 모델 field명/생성자 차이 | dependency 추가 뒤 generated model compile과 request mapping test로 확인하고 추측 API를 남기지 않음 |
| compileOnly 누출 | consumer omission 및 publication verifier를 모두 실행 |
| callback이 필수값을 무효화 | callback 후 local invariant 재검증 |
| client/HTTP engine 이중 close | 성공·예외·cancellation lifecycle test와 ownership KDoc |
| emulator 미지원 | 기본 smoke skip, 미지원은 `N/A/UNVERIFIED`, 실제 AWS credential gate 별도 |
| 관리 API 범위 확장 | Iceberg/data-plane 및 policy/replication helper를 후속 이슈로 남김 |

## 완료 정의

- [ ] 구현·테스트·문서 변경이 Issue #311의 bounded scope에 있다.
- [ ] fresh local verification이 통과하고 결과가 evidence에 기록되어 있다.
- [ ] PR head/CI/review/mergeability를 재확인했으며 merge는 하지 않았다.
- [ ] known gap(실제 AWS IAM/resource smoke, emulator fidelity 등)이 명시되어 있다.
