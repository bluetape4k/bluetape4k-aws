# Issue #269 RDS IAM Core Helper Plan

Date: 2026-06-30
Spec: `docs/superpowers/specs/2026-06-30-issue-269-rds-iam-core-design.md`

## Task 1: Add Failing Core API Tests

complexity: medium

- Add `:bluetape4k-aws-java` unit tests for the intended
  `io.bluetape4k.aws.rds` API before production code.
- Cover:
  - redacted token factory and `toString()`;
  - blank token validation;
  - request validation for blank region/hostname/username and invalid port;
  - request-to-AWS-SDK mapping through a fake or caller-supplied
    `RdsUtilities` boundary where practical;
  - generator failure wrapping with no token leakage;
  - exception type extending the repo AWS exception base.
- Run the targeted test task and record the expected RED failure before adding
  production code.
- Verification evidence:
  `./gradlew :bluetape4k-aws-java:test --tests '*AwsRdsIam*' --no-configuration-cache`
  fails because the new core API does not exist yet.

## Task 2: Implement Core RDS IAM Token API

complexity: medium

- Add `aws-java/src/main/kotlin/io/bluetape4k/aws/rds/` package.
- Implement:
  - `AwsRdsIamAuthToken`;
  - `awsRdsIamAuthTokenOf`;
  - `AwsRdsIamAuthTokenRequest`;
  - `AwsRdsIamAuthTokenGenerator`;
  - `AwsSdkRdsIamAuthTokenGenerator`;
  - `AwsRdsIamAuthTokenException`.
- Add `compileOnly(libs.aws2.rds)` and `testImplementation(libs.aws2.rds)` to
  `aws-java`.
- Keep `RdsUtilities` caller-managed when injected.
- Keep messages redaction-safe and use endpoint host/port as the only request
  context in failure messages.
- Add English KDoc to public API.
- Verification evidence:
  targeted core tests pass.

## Task 3: Add Failing Exposed Reuse Regression

complexity: medium

- Add or update `aws-exposed` tests proving the Exposed SDK-backed generator
  delegates through the core generator path or adapts core token behavior.
- Prefer an observable adapter assertion: inject a failing `RdsUtilities`,
  verify the exposed exception remains redaction-safe, and verify its cause
  chain includes the core `io.bluetape4k.aws.rds.AwsRdsIamAuthTokenException`.
- Preserve existing public Exposed provider factory lambda call sites without
  ambiguous overloads.
- Add a regression assertion that Exposed generator failure messages remain
  redaction-safe after delegation.
- Run the targeted Exposed test task and record the expected RED failure before
  changing Exposed production code.
- Verification evidence:
  `./gradlew :bluetape4k-aws-exposed:test --tests '*AwsRdsIam*' --no-configuration-cache`
  fails for the new reuse expectation.

## Task 4: Refactor Exposed To Reuse Core Generator

complexity: high

- Add `implementation(project(":bluetape4k-aws-java"))` to `aws-exposed`.
- Keep `AwsRdsIamAuthenticationProperties`,
  `AwsDatabasePasswordProvider`, `AwsDatabasePasswordProviders`, and
  `RdsIamRefreshingDataSource` in `aws-exposed`.
- Keep Exposed public generator/request/exception names as compatibility
  wrappers or adapters unless a typealias proves safe for Kotlin and JVM use.
- Update `AwsSdkRdsIamAuthTokenGenerator` in `aws-exposed` to delegate to
  `io.bluetape4k.aws.rds.AwsSdkRdsIamAuthTokenGenerator`.
- Adapt core `AwsRdsIamAuthToken` to Exposed `AwsSecretString` only at the
  JDBC password provider boundary.
- Avoid overloads that make `AwsDatabasePasswordProviders.rdsIam(...)` lambda
  call sites ambiguous.
- Verification evidence:
  existing and new Exposed RDS IAM tests pass.

## Task 5: Update Public Documentation And Chart

complexity: medium

- Update root `README.md` and `README.ko.md`:
  - `bluetape4k-aws-java` module row includes Java SDK-backed RDS IAM token
    helpers;
  - Java SDK installation snippet includes optional
    `software.amazon.awssdk:rds`;
  - `bluetape4k-aws-kotlin` remains clear that no native RDS IAM facade is
    added.
- Update `aws-exposed/README.md` and `aws-exposed/README.ko.md` to point to
  the shared Java SDK-backed generator while keeping JDBC refresh guidance.
- Inspect `docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.svg`.
  If it shows RDS IAM as Exposed-only or omits Java support, update the SVG and
  regenerate the matching PNG.
- For changed diagrams, run XML parse, PNG render, and full-size visual
  inspection.
- Verification evidence:
  `git diff --check`, README image link review, and diagram render/inspection
  evidence for changed assets.

## Task 6: Compile, Test, And Review

complexity: medium

- Run:
  - `./gradlew :bluetape4k-aws-java:compileKotlin :bluetape4k-aws-java:compileTestKotlin --no-configuration-cache`
  - `./gradlew :bluetape4k-aws-java:test --tests '*AwsRdsIam*' --no-configuration-cache`
  - `./gradlew :bluetape4k-aws-exposed:compileKotlin :bluetape4k-aws-exposed:compileTestKotlin --no-configuration-cache`
  - `./gradlew :bluetape4k-aws-exposed:test --tests '*AwsRdsIam*' --no-configuration-cache`
  - `git diff --check`
- Attempt detekt only if the project exposes the task for the targeted modules.
- Review the final diff against the spec and issue #269 acceptance criteria.
- Verification evidence:
  command exit codes, test counts/failures, and any explicit verification gaps.

## Task 7: Commit, PR, And Metadata Parity

complexity: medium

- Commit with Lore trailers in English.
- Push `feat/aws-rds-iam-core`.
- Create PR linked to issue #269, assigned to `debop`.
- Mirror issue milestone `0.5.0` and labels when GitHub supports them.
- Ensure the final PR body `##` section is `## DoD Status`.
- Verify live issue and PR metadata with `gh issue view` and `gh pr view`.
- Monitor required CI before merge readiness.

## Step 3-R Review Notes

### Codex Plan Review

| Priority | Finding | Decision |
|---|---|---|
| P0 | The original Exposed reuse test idea could pass without proving core reuse. | Accepted. Task 3 now requires an observable adapter assertion using a failing `RdsUtilities` and a core exception in the cause chain. |
| P1 | Provider factory overloads can create ambiguous Kotlin lambda calls. | Accepted. Task 4 keeps the existing Exposed generator signature and avoids overloads unless implementation proves they are unambiguous. |
| P1 | Chart work could become broader than #269. | Accepted. Task 5 limits visual edits to the service coverage chart only when its current semantics conflict with the new `aws-java` RDS IAM support. |
| P1 | TDD RED evidence must be tied to missing behavior, not typo/setup failures. | Accepted. Tasks 1 and 3 require recording expected RED failures before production edits. |

Convergence: P0 = 0, P1 = 0 after accepted edits.
