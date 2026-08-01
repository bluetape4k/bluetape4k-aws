# Issue 295 검토 - RDS IAM JDBC helper

- 날짜: 2026-06-30 KST
- 범위:
  - `aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsJdbcDataSourceFactory.kt`
  - `aws-exposed/src/test/kotlin/io/bluetape4k/aws/exposed/AwsRdsIamAuthenticationTest.kt`
- Issue: #295

## 검토 내용

- `bluetape4k-jdbc`는 `RefreshingJdbcPasswordDataSource`를 제공하며 `bluetape4k-aws`는 `io.github.bluetape4k:bluetape4k-jdbc:1.11.1-SNAPSHOT:20260630.110106-1`을 해석한다.
- `aws-exposed`는 RDS IAM 연결 생성용 하위 `DriverManager` wrapper를 더 이상 소유하지 않는다.
- AWS adapter는 `AwsDatabasePasswordProviders.rdsIam(...)`을 통한 AWS 전용 token 생성을 계속 소유한다.
- 범용 helper에는 `String?` password supplier와 AWS 전용 null-token 메시지만 전달한다.
- Hikari RDS IAM 경로는 `dataSource` 속성을 사용하므로 호출자가 제공한 credentials로 refresh-aware DataSource를 우회할 수 없다.
- 과거 문서는 예전 내부 wrapper를 설계 배경으로 언급하지만 현재 source/test는 참조하지 않는다.

## 검증

- `:bluetape4k-aws-exposed:dependencyInsight --dependency bluetape4k-jdbc --configuration testRuntimeClasspath`: PASS, `1.11.1-SNAPSHOT:20260630.110106-1`
- `:bluetape4k-aws-exposed:test --no-daemon --stacktrace`: PASS, 17 tests, 0 failures, 0 errors
- `:bluetape4k-aws-exposed:compileTestKotlin --warning-mode all --no-daemon --stacktrace`: PASS
- `git diff --check`: PASS
- `RdsIamRefreshingDataSource`와 `DriverManager` source/test 검색: PASS, 현재 hit 없음

## P0/P1 판정

- P0: 0
- P1: 0
