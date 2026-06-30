# Issue 295 Review - RDS IAM JDBC helper

- Date: 2026-06-30 KST
- Scope:
  - `aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsJdbcDataSourceFactory.kt`
  - `aws-exposed/src/test/kotlin/io/bluetape4k/aws/exposed/AwsRdsIamAuthenticationTest.kt`
- Issue: #295

## Review Notes

- `bluetape4k-jdbc` now provides `RefreshingJdbcPasswordDataSource`, and `bluetape4k-aws` resolves `io.github.bluetape4k:bluetape4k-jdbc:1.11.1-SNAPSHOT:20260630.110106-1`.
- `aws-exposed` no longer owns the low-level `DriverManager` wrapper for RDS IAM connection creation.
- The AWS adapter still owns AWS-specific token generation through `AwsDatabasePasswordProviders.rdsIam(...)`.
- The generic helper receives only a `String?` password supplier and the AWS-specific null-token message.
- The Hikari RDS IAM path still uses the `dataSource` property, so caller-supplied credentials cannot bypass the refresh-aware DataSource path.
- Historical docs still mention the old internal wrapper as past design context; source and current tests no longer reference it.

## Validation

- `:bluetape4k-aws-exposed:dependencyInsight --dependency bluetape4k-jdbc --configuration testRuntimeClasspath`: PASS, resolved `1.11.1-SNAPSHOT:20260630.110106-1`
- `:bluetape4k-aws-exposed:test --no-daemon --stacktrace`: PASS, 17 tests, 0 failures, 0 errors
- `:bluetape4k-aws-exposed:compileTestKotlin --warning-mode all --no-daemon --stacktrace`: PASS
- `git diff --check`: PASS
- Source/test search for `RdsIamRefreshingDataSource` and `DriverManager`: PASS, no current source/test hits

## P0/P1 Verdict

- P0: 0
- P1: 0
