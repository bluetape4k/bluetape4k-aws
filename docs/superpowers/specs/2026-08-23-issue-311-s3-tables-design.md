# 이슈 #311 S3 Tables 관리 API helper 설계

## 설계 상태

- 상태: 사용자 승인 후 구현 기준선
- 범위: Epic #501의 다음 미완료 하위 이슈인 Issue #311
- 기준일: 2026-08-23
- 구현 전제: 이 문서와 실행 계획의 검토 결과를 기록한 뒤 TDD로 구현한다.

## SPW 게이트

### SPW-01 — 요구사항과 경계

Issue #311의 목표는 AWS S3 Tables 관리 API를 Java SDK v2와 AWS SDK for Kotlin에서
얇게 사용할 수 있게 하는 것이다. 이번 범위는 table bucket, namespace, table의 생성·조회·목록·삭제와
필요한 request DSL, client lifecycle helper, consumer compile 계약 및 사용 문서다.

다음은 명시적으로 제외한다.

- Iceberg 엔진, table data-plane, SQL, Athena/Glue/Redshift 통합 facade
- 정책·암호화·복제·maintenance·tagging 전용 도메인 abstraction
- helper가 임의로 정하는 retry, polling, 자동 삭제 또는 비용이 발생하는 운영 동작
- 로컬 emulator가 S3 Tables API를 지원한다는 주장

AWS SDK raw request/response와 예외를 보존하고, 후속 이슈에서 확장 가능한 좁은 경계를 유지한다.

### SPW-02 — 저장소와 외부 근거

- `aws-java`는 service SDK를 `compileOnly`로 선언하고 sync, `CompletableFuture`, coroutine 계층을 분리한다.
- `aws-kotlin`은 native suspend API를 직접 호출하고 client를 명시적으로 닫는다.
- 기존 S3, Step Functions, S3 Vectors helper의 factory·request builder·`ShutdownQueue` 패턴을 따른다.
- [Java S3TablesClient](https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/s3tables/S3TablesClient.html)와
  [Kotlin S3TablesClient](https://docs.aws.amazon.com/sdk-for-kotlin/api/latest/s3tables/aws.sdk.kotlin.services.s3tables/-s3-tables-client/index.html)는
  table bucket, namespace, table 관리 operation과 paginator/suspend API를 제공한다.
- [S3 Tables API operation 목록](https://docs.aws.amazon.com/AmazonS3/latest/API/API_Operations_Amazon_S3_Tables.html)은
  관리 API가 S3 object API와 별도 surface임을 명시한다. 실제 AWS 호출은 operation별 IAM 권한과 비용·수명 정책을 따른다.

### SPW-03 — 대안 비교

| 대안 | 장점 | 문제 | 결정 |
|---|---|---|---|
| raw SDK만 문서화 | 구현량 최소 | 저장소의 lifecycle·coroutine 관용구와 소비자 compile 계약이 빠짐 | 제외 |
| service별 별도 도메인 모델 | 호출자가 편리함 | SDK 필드·예외를 복제하고 새 API surface를 고정함 | 제외 |
| raw SDK extension + request DSL | 얇은 대칭 surface, SDK 업데이트 대응, 테스트 용이 | 소비자가 service SDK를 직접 추가해야 함 | 채택 |
| Iceberg/Athena 통합 facade | end-to-end 예제 가능 | Issue #311의 관리 API 범위를 넘고 추가 의존성과 권한이 필요함 | 제외 |

### SPW-04 — 공개 API와 불변식

공개 패키지는 Java `io.bluetape4k.aws.s3tables`, Kotlin
`io.bluetape4k.aws.kotlin.s3tables`로 고정한다.

핵심 operation은 다음과 같다.

- table bucket: create/list/get/delete
- namespace: create/list/get/delete
- table: create/list/get/delete

목록 operation은 paginator 전체가 아니라 raw SDK의 한 페이지를 반환한다. Java/Kotlin 모두
`continuationToken`과 service별 `maxBuckets`·`maxNamespaces`·`maxTables`, prefix/type 같은
SDK 필터를 노출하고, caller가 다음 페이지를 명시적으로 요청한다. 따라서 helper가 목록 완전성을
암시하거나 내부적으로 무한 요청을 하지 않는다.

명시 인자는 SDK builder callback보다 먼저 적용하고 callback을 마지막에 실행한다. callback 이후에도
필수 ARN, namespace path, table name 같은 local invariant를 다시 검사한다. blank 식별자는
`IllegalArgumentException`으로 즉시 거부하며 AWS 선택 필드는 raw SDK에 맡긴다.

`CreateTable`의 `format`은 `ICEBERG`를 기본값으로 설정하고 callback이 유효한 SDK 값으로 override할 수
있도록 한다. callback이 최종 format을 제거하면 거부한다. `GetTable`은 `tableArn` 하나 또는
`tableBucketARN/tableBucketArn + namespace + name` 세 필드 중 정확히 하나의 selector만 허용하며,
두 방식을 섞거나 모두 비우면 거부한다. `DeleteTable`은 선택적인 `versionToken`을 노출하고 생략 시
AWS의 기본 삭제 의미를 그대로 따른다.

Java는 sync extension, async `CompletableFuture`, async coroutine `.await()`와 application/short-lived
factory를 제공한다. application-scoped Java factory가 만든 client는 `ShutdownQueue`에 등록되며
caller는 필요하면 조기에 닫을 수 있다. Kotlin은 `s3TablesClientOf`, `withS3TablesClient`, native
suspend extension을 제공한다. application-scoped client는 caller가 닫고, `with...`는
성공·예외·cancellation 모두에서 service client만 닫는다.
caller-owned HTTP client와 공유 default HTTP client는 helper가 닫지 않는다.

### SPW-05 — 검증과 호환성

- compileOnly consumer fixture가 Java/Kotlin service SDK를 명시적으로 추가할 때만 컴파일된다.
- request DSL, sync/async/suspend delegation, callback 최종 매핑, lifecycle close ownership을 unit test한다.
- module targeted test → full module test → detekt/build → publication dependency leak → manual contract 순으로 검증한다.
- real AWS smoke는 명시적인 credential, region, resource 입력과 별도 opt-in property가 있을 때만 실행한다. 기본 실행은 skip이며,
  local emulator는 `N/A/UNVERIFIED`로 기록한다. read-only smoke(`-Ps3TablesReadOnlySmoke`)와 mutating smoke(`-Ps3TablesMutatingSmoke`)
  를 분리한다. read-only는 `S3_TABLES_READ_ONLY_REGION`과 `S3_TABLES_READ_ONLY_TABLE_BUCKET_ARN`이 필요하고,
  mutating은 `S3_TABLES_EXPECTED_ACCOUNT_ID`, `S3_TABLES_MUTATING_REGION`, `S3_TABLES_MUTATING_PREFIX`가 필요하다.
  mutating 경로는 첫 생성 전에 STS `GetCallerIdentity.account`가 기대 account와 일치하는지 확인하고 불일치하면 fail-closed 한다.
  smoke는 account/region 고정 입력, 고유 prefix, 이번 실행에서 생성한 bucket·namespace·table만 역순으로
  삭제하는 `finally`, timeout, sanitized evidence를 모두 요구하고 기존 리소스를 삭제하지 않는다.
- AWS 문서의 [Create table bucket](https://docs.aws.amazon.com/us_en/AmazonS3/latest/userguide/s3-tables-buckets-create.html),
  [CreateNamespaceRequest](https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/s3tables/model/CreateNamespaceRequest.html),
  [CreateTableRequest](https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/s3tables/model/CreateTableRequest.html)을
  request field 및 real smoke 경계의 근거로 사용한다.

## 수용 기준

1. Java/Kotlin catalog alias와 compileOnly/publication BOM 계약이 통과한다.
2. 두 모듈에서 위 12개 관리 operation을 raw SDK response로 호출할 수 있다.
3. request helper가 required field와 callback 최종 override를 검증한다.
4. Java async/coroutine와 Kotlin suspend가 cancellation·exception을 보존하고 lifecycle 소유권을 지킨다.
5. consumer fixture, targeted/full test, detekt, build, publication verifier가 fresh evidence로 통과한다.
6. EN/KO README/manual과 CHANGELOG가 S3 Tables의 관리 범위와 Iceberg/Athena 경계를 설명한다.
7. 실제 AWS 또는 emulator 호환성을 검증하지 못한 경우 이를 PASS로 포장하지 않는다.

## 설계 Gate 결과

| Gate | 상태 | 근거 |
|---|---|---|
| SPW-01 | PASS | Issue #311 live 요구사항과 비목표를 고정함 |
| SPW-02 | PASS | 저장소 패턴과 AWS 공식 API 문서를 연결함 |
| SPW-03 | PASS | raw extension/request DSL 대안을 선택하고 통합 facade를 제외함 |
| SPW-04 | PASS | package, operation, invariant, lifecycle ownership을 명시함 |
| SPW-05 | PASS | 테스트·publication·smoke·문서 수용 기준을 명시함 |
