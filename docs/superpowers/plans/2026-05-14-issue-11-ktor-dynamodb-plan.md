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
- [x] Run Claude advisor review if local `claude` CLI is available.
- [x] Integrate accepted spec findings.
- [x] Run Step 3-R plan review.
- [x] Run Claude advisor review if local `claude` CLI is available.
- [x] Integrate accepted plan findings.
- [ ] Commit spec and plan before implementation.

### T1 - Build Wiring

- [ ] Promote `project(":aws-kotlin")` from `compileOnly` to `api` in
      `aws-ktor`.
- [ ] Add `compileOnly(libs.aws.kotlin.dynamodb)` to `aws-ktor`.
- [ ] Add `testImplementation(libs.aws.kotlin.dynamodb)` to `aws-ktor`.
- [ ] Keep `project(":aws")` as `api` for existing S3/SQS/SigV4 code until #85
      defines the migration path.
- [ ] Keep `:aws-kotlin` as the helper module dependency and reuse its public
      helpers: `dynamoDbClientOf`, `withDynamoDbClient`,
      `DynamoItemMapper`, table helpers, and batch helpers.
- [ ] Compile `:aws-ktor:compileKotlin` as a wiring-only sanity check.

### T2 - `:aws-kotlin` Mapper Additions

- [ ] Add `DynamoItemReader<T>` to `:aws-kotlin`.
- [ ] Add unit tests for `DynamoItemReader<T>` usage with a simple entity.
- [ ] Compile `:aws-kotlin:compileKotlin`.
- [ ] Run `:aws-kotlin:test`.

### T3 - Runtime And Plugin

- [ ] Add package `io.bluetape4k.aws.ktor.dynamodb`.
- [ ] Add `DynamoDbKtorPluginConfig`.
- [ ] Add `DynamoDbKtorRuntime`.
- [ ] Add `DynamoDbKtorPlugin`.
- [ ] Add `DynamoDbKtorRuntimeKey` as
      `AttributeKey<DynamoDbKtorRuntime>` stored in `Application.attributes`.
- [ ] Add `Application.dynamoDb()`.
- [ ] Support injected vs plugin-owned AWS Kotlin SDK `DynamoDbClient`.
- [ ] Close only plugin-owned clients.
- [ ] Hook `ApplicationStarted` to run registered table auto-creation when
      configured.
- [ ] Hook `ApplicationStopping` to close plugin-owned clients with bounded
      timeout; document the `runBlocking(Dispatchers.IO)` suspend-close bridge
      if used.

### T4 - Table Model And Repository

- [ ] Add explicit table definition model for optional auto-creation.
- [ ] If table definition is a `data class`, apply the repo Serializable and
      `serialVersionUID` rule.
- [ ] Add repository contract using `:aws-kotlin`, AWS Kotlin SDK item maps,
      and explicit mappers.
- [ ] Add v1 `save`, `findById`, `deleteById`, `scan`, and `query`.
- [ ] Defer `count`, `batchGet`, advanced update expressions, schema
      verification, and named-client registry.

### T5 - Tests

- [ ] Add config validation tests.
- [ ] Add lifecycle/client ownership tests.
- [ ] Add a test proving injected clients are not closed.
- [ ] Add a test proving `autoCreateTables = true` creates registered tables on
      startup.
- [ ] Add a test proving existing tables are skipped idempotently.
- [ ] Add a test proving `autoCreateTables = false` leaves tables untouched.
- [ ] Add LocalStack DynamoDB integration test for save/find.
- [ ] Add scan or query `Flow` test.
- [ ] Use Awaitility/bounded polling instead of fixed sleeps.
- [ ] Follow existing `aws-ktor` test package/tag conventions.

### T6 - Documentation

- [ ] Update `aws-ktor/README.md`.
- [ ] Update `aws-ktor/README.ko.md`.
- [ ] Mention AWS Kotlin SDK dependency requirements.
- [ ] Include a consumer dependency snippet for `aws.sdk.kotlin:dynamodb`.
- [ ] Mention AWS Kotlin DynamoDB Mapper Developer Preview is not the default.
- [ ] Add English KDoc with summary and behavior/contract notes for new public
      APIs.

### T7 - Verification

- [ ] `git diff --check`
- [ ] `./gradlew :aws-kotlin:detekt`
- [ ] `./gradlew :aws-kotlin:test`
- [ ] `./gradlew :aws-ktor:detekt`
- [ ] `./gradlew :aws-ktor:compileKotlin :aws-ktor:compileTestKotlin`
- [ ] `./gradlew :aws-ktor:test --tests 'io.bluetape4k.aws.ktor.dynamodb.*'`
- [ ] `./gradlew :aws-ktor:test`
- [ ] Tier 4 code review.
- [ ] Claude code review advisor if available.

### T8 - Knowledge And PR

- [ ] Add `docs/lessons/2026-05-14-issue-11-ktor-dynamodb.md`.
- [ ] Commit implementation with Lore trailers.
- [ ] Push branch.
- [ ] Open draft PR in English.
- [ ] Run post-PR dual review / merge gate if merge-readiness is requested.

## Acceptance Checklist

- [ ] `DynamoDbKtorPlugin` can be installed in a Ktor application.
- [ ] Runtime exposes AWS Kotlin SDK `DynamoDbClient`.
- [ ] Repository path uses `:aws-kotlin`, official AWS Kotlin SDK DynamoDB
      types, and explicit mappers.
- [ ] Optional table auto-creation is explicit.
- [ ] Query/scan expose Kotlin `Flow`.
- [ ] Tests prove lifecycle, ownership, and LocalStack CRUD behavior.
- [ ] README locale pair is current.

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
