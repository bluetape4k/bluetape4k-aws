# Issue #11 Ktor DynamoDB Plan

Date: 2026-05-14
Spec: `docs/superpowers/specs/2026-05-14-issue-11-ktor-dynamodb-design.md`
Issue: https://github.com/bluetape4k/bluetape4k-aws/issues/11
Related migration issue: https://github.com/bluetape4k/bluetape4k-aws/issues/85

## Classification

Type A - Full Design.

Signals:

- `feat:` issue.
- New Ktor server plugin.
- New DynamoDB repository/runtime layer.
- Build, docs, tests, and lesson updates across multiple files.

## Execution Rules

- Work inside `.worktrees/issue-11-ktor-dynamodb`.
- Use `bluetape4k-aws` `:aws-kotlin` plus official AWS SDK for Kotlin
  DynamoDB as the primary DynamoDB surface.
- Do not make Java SDK v2 Enhanced Client the default Ktor implementation.
- Do not duplicate existing `:aws-kotlin` client factories, table utilities,
  mappers, or DynamoDB helpers in `aws-ktor`.
- Do not migrate existing Ktor S3/SQS/SigV4 implementations in this issue;
  that work is tracked by #85.
- Keep public KDoc and GitHub/CHANGELOG artifacts in English.
- Update `aws-ktor/README.md` and `aws-ktor/README.ko.md` together.
- Add a lesson before PR publication.

## Task List

### T0 - Design Review

- [x] Run Step 2-R spec review.
- [x] Run mandatory Claude advisor review.
- [x] Integrate accepted spec findings.
- [x] Run Step 3-R plan review.
- [x] Run mandatory Claude advisor review.
- [x] Integrate accepted plan findings.
- [x] Commit spec and plan before implementation.

### T1 - Build Wiring

- [x] Promote `project(":aws-kotlin")` from `compileOnly` to `api` in
      `aws-ktor`.
- [x] Add `compileOnly(libs.aws.kotlin.dynamodb)` to `aws-ktor`.
- [x] Add `testImplementation(libs.aws.kotlin.dynamodb)` to `aws-ktor`.
- [x] Keep `project(":aws")` as `api` for existing S3/SQS/SigV4 code until #85
      defines the migration path.
- [x] Keep `:aws-kotlin` as the helper module dependency and reuse its public
      helpers: `dynamoDbClientOf`, `withDynamoDbClient`,
      `DynamoItemMapper`, table helpers, and batch helpers.
- [x] Compile `:aws-ktor:compileKotlin` as a wiring-only sanity check.

### T2 - `:aws-kotlin` Mapper Additions

- [x] Add `DynamoItemReader<T>` to `:aws-kotlin`.
- [x] Add unit tests for `DynamoItemReader<T>` usage with a simple entity.
- [x] Compile `:aws-kotlin:compileKotlin`.
- [x] Run `:aws-kotlin:test`.

### T3 - Runtime And Plugin

- [x] Add package `io.bluetape4k.aws.ktor.dynamodb`.
- [x] Add `DynamoDbKtorPluginConfig`.
- [x] Add `DynamoDbKtorRuntime`.
- [x] Add `DynamoDbKtorPlugin`.
- [x] Add `DynamoDbKtorRuntimeKey` as
      `AttributeKey<DynamoDbKtorRuntime>` stored in `Application.attributes`.
- [x] Add `Application.dynamoDb()`.
- [x] Support injected vs plugin-owned AWS Kotlin SDK `DynamoDbClient`.
- [x] Close only plugin-owned clients.
- [x] Hook `ApplicationStarted` to run registered table auto-creation when
      configured.
- [x] Hook `ApplicationStopping` to close plugin-owned clients with bounded
      timeout; document the `runBlocking(Dispatchers.IO)` suspend-close bridge
      if used.

### T4 - Table Model And Repository

- [x] Add explicit table definition model for optional auto-creation.
- [x] Avoid a table definition `data class`; no serialization contract is
      needed for the function-backed table builder.
- [x] Add repository contract using `:aws-kotlin`, AWS Kotlin SDK item maps,
      and explicit mappers.
- [x] Add v1 `save`, `findById`, `deleteById`, `scan`, and `query`.
- [x] Defer `count`, `batchGet`, advanced update expressions, schema
      verification, and named-client registry.

### T5 - Tests

- [x] Add config validation tests.
- [x] Add lifecycle/client ownership tests.
- [x] Add a test proving injected clients are not closed.
- [x] Add a test proving `autoCreateTables = true` creates registered tables on
      startup.
- [x] Add a test proving existing tables are skipped idempotently.
- [x] Add a test proving `autoCreateTables = false` leaves tables untouched.
- [x] Add LocalStack DynamoDB integration test for save/find.
- [x] Add scan or query `Flow` test.
- [x] Use Awaitility/bounded polling instead of fixed sleeps.
- [x] Follow existing `aws-ktor` test package/tag conventions.

### T6 - Documentation

- [x] Update `aws-ktor/README.md`.
- [x] Update `aws-ktor/README.ko.md`.
- [x] Mention AWS Kotlin SDK dependency requirements.
- [x] Include a consumer dependency snippet for `aws.sdk.kotlin:dynamodb`.
- [x] Mention AWS Kotlin DynamoDB Mapper Developer Preview is not the default.
- [x] Add English KDoc with summary and behavior/contract notes for new public
      APIs.

### T7 - Verification

- [x] `git diff --check`
- [x] `./gradlew :aws-kotlin:detekt` - module task unavailable; verified root `./gradlew detekt` returns `NO-SOURCE`.
- [x] `./gradlew :aws-kotlin:test`
- [x] `./gradlew :aws-ktor:detekt` - module task unavailable; verified root `./gradlew detekt` returns `NO-SOURCE`.
- [x] `./gradlew :aws-ktor:compileKotlin :aws-ktor:compileTestKotlin`
- [x] `./gradlew :aws-ktor:test --tests 'io.bluetape4k.aws.ktor.dynamodb.*'`
- [x] `./gradlew :aws-ktor:test`
- [x] `./gradlew :aws:compileKotlin :aws-kotlin:compileKotlin :aws-spring-boot:compileKotlin :aws-ktor:compileKotlin :aws-kotlin:compileTestKotlin :aws-spring-boot:compileTestKotlin :aws-ktor:compileTestKotlin`
- [x] `./gradlew :aws:test :aws-kotlin:test :aws-spring-boot:test :aws-ktor:test`
- [x] Verified AWS modules use `bluetape4k-jackson3`, `io.bluetape4k.jackson3`,
      and `tools.jackson`.
- [x] Tier 4 code review.
- [x] Mandatory Claude code review advisor.

### T8 - Knowledge And PR

- [x] Add `docs/lessons/2026-05-14-issue-11-ktor-dynamodb.md`.
- [ ] Commit implementation with Lore trailers.
- [ ] Push branch.
- [ ] Open draft PR in English.
- [ ] Run post-PR dual review / merge gate if merge-readiness is requested.

## Acceptance Checklist

- [x] `DynamoDbKtorPlugin` can be installed in a Ktor application.
- [x] Runtime exposes AWS Kotlin SDK `DynamoDbClient`.
- [x] Repository path uses `:aws-kotlin`, official AWS Kotlin SDK DynamoDB
      types, and explicit mappers.
- [x] Optional table auto-creation is explicit.
- [x] Query/scan expose Kotlin `Flow`.
- [x] Tests prove lifecycle, ownership, and LocalStack CRUD behavior.
- [x] README locale pair is current.

## Step 3-R Review Notes

- Claude advisor artifact:
  `.omx/artifacts/claude-issue-11-ktor-dynamodb-plan-20260514-201436.md`.
- P0/P1 accepted:
  - Add table auto-creation tests.
  - Add explicit `ApplicationStarted` / `ApplicationStopping` lifecycle tasks.
  - Split `:aws-kotlin` mapper work from `aws-ktor` plugin/repository work.
  - Preserve `project(":aws")` as `api` until #85.
  - Add detekt, `:aws-kotlin:test`, and English KDoc tasks.
- Rejected:
  - None.
- Convergence: P0 = 0, P1 = 0 after edits.

## Implementation Review Notes

- Claude advisor artifact:
  `.omx/artifacts/claude-issue-11-ktor-dynamodb-code-review-20260514-203024.md`.
- Accepted findings:
  - Changed `DynamoDbKtorRuntimeConfig` from `data class` to plain `class`.
  - Treated concurrent DynamoDB table creation `ResourceInUseException` as an
    idempotent auto-create race and wait for readiness.
  - Added `deleteById` coverage to the LocalStack repository test.
  - Documented synchronous Ktor lifecycle suspend bridges in plugin comments.
- Follow-up mandatory review:
  - `.omx/artifacts/ask-claude-code-review-issue-11-ktor-dynamodb-postfix-20260514-203547.md`
  - Accepted P2 findings: log plugin-owned close timeout, wait for readiness on
    existing tables, and add plugin-owned client close coverage.
  - `.omx/artifacts/ask-claude-code-review-issue-11-ktor-dynamodb-final-jackson3-20260514-205134.md`
  - Accepted P2 findings: Dependabot Jackson 3 grouping, interruptible bounded
    client close, and repository `save`/`put` duplication cleanup.
  - `.omx/artifacts/ask-claude-code-review-issue-11-ktor-dynamodb-final-clean-20260514-205816.md`
  - Final verdict: P0 = 0, P1 = 0, P2 = 0; approve / ready to merge.
- Rejected:
  - None.
- Convergence: P0 = 0, P1 = 0, P2 = 0 after edits.
