# CLAUDE.md — bluetape4k-aws

Guidance for Claude Code when working in this repository.

## Project Overview

`bluetape4k-aws` is a standalone Kotlin/JVM library repository providing AWS SDK wrappers for the
bluetape4k ecosystem. It wraps both the **AWS Java SDK v2** and the **AWS Kotlin SDK**, adds
Kotlin Coroutines support, and integrates with Spring Boot 4 and Ktor 3.

- **Group**: `io.github.bluetape4k.aws`
- **Base version**: `0.1.0` (published as `0.1.0-SNAPSHOT` on Maven Central Snapshots)
- **Publishing**: Maven Central via `nmcp` (NMCP Aggregation plugin) with `publishingType=AUTOMATIC`

## Repository Layout

| Module | Status | Description |
|---|---|---|
| `aws/` | stable | AWS Java SDK v2 wrappers — sync, async (`CompletableFuture`), and Coroutines extensions for DynamoDB, S3, SES, SESv2, SNS, SQS, KMS, CloudWatch, CloudWatch Logs, Kinesis, STS |
| `aws-kotlin/` | stable | AWS Kotlin SDK wrappers — native `suspend` functions for DynamoDB, S3, SES, SESv2, SNS, SQS, KMS, CloudWatch, CloudWatch Logs, Kinesis, STS; DSL builders |
| `aws-spring-boot/` | WIP skeleton | Spring Boot 4 auto-configuration for AWS services (no awspring dependency — pure Coroutines implementation) |
| `aws-ktor/` | WIP skeleton | Ktor 3 client/server integration for AWS services |

> Integration tests for `aws` and `aws-kotlin` use LocalStack via Testcontainers.
> Select emulator with `-Dbluetape4k.aws.emulator=localstack|floci` (default: `localstack`).

## Build Commands

```bash
# Compile all modules (no tests)
./gradlew build -x test --parallel

# Compile + test a specific module
./gradlew :aws:test
./gradlew :aws-kotlin:test
./gradlew :aws-spring-boot:test

# Run a single test class
./gradlew :aws:test --tests "io.bluetape4k.aws.s3.S3ClientSupportTest"

# Run with Floci emulator
./gradlew :aws:test -Dbluetape4k.aws.emulator=floci

# Full build (compile + test all)
./gradlew build

# Detekt static analysis
./gradlew detekt

# Publish SNAPSHOT to Maven Central Snapshots
./gradlew publishBluetapeAwsPublicationToCentralPortal

# Publish RELEASE (clear snapshotVersion)
./gradlew publishBluetapeAwsPublicationToCentralPortal -PsnapshotVersion=
```

## Kotlin Edit Workflow (MANDATORY)

Before modifying any class: use `ide_find_references` or `get_impact_radius_tool` to identify
all affected files.

After every `.kt` edit:

1. `ide_diagnostics` — check import errors and `@Deprecated` warnings
2. Import errors → fix with `ide_optimize_imports` or `lsp_code_actions`
3. `@Deprecated` → apply Quick Fix via `lsp_code_actions` — never leave unresolved
4. Build/compile only after passing the above steps

## Key Design Patterns

### Assert vs Require (CRITICAL — do NOT change exception types)

- `assertXxx()` → `AssertionError` (internal invariants; `@Deprecated` in new code)
- `requireXxx()` → `IllegalArgumentException` (parameter validation — always use this)

### Coroutines-First

All async work uses Kotlin Coroutines. The `aws` module wraps `CompletableFuture` with `.await()`.
The `aws-kotlin` module uses native `suspend` functions from the AWS Kotlin SDK directly.
Never use `runBlocking` in production code. Wrap blocking AWS calls with `withContext(Dispatchers.IO)`.

### AWS SDK Service Dependencies

Both `aws` and `aws-kotlin` declare AWS service SDKs as `compileOnly`. Consumers must add
the service runtime dependencies they actually use. This keeps the library itself lightweight.

### Client Lifecycle (aws-kotlin)

AWS Kotlin SDK clients hold connection pools and threads. Always close them:
- Short-lived: use `withXxxClient { }` (closes automatically, even on cancellation)
- Long-lived: call `close()` explicitly at application shutdown

### Virtual Threads

Never use `@Synchronized` or `synchronized { }`. Use `reentrantLock()` if mutual exclusion is needed.

## After Code Changes

- [ ] Run `ide_diagnostics` for every modified `.kt` file
- [ ] Compile changed module: `./gradlew :aws:build -x test` (or relevant module)
- [ ] Run tests for changed module: `./gradlew :<module>:test`
- [ ] Update both `README.md` and `README.ko.md` for every changed module
- [ ] Add/update KDoc for all new or modified public APIs

## Before Creating a PR (MANDATORY)

- [ ] All module tests pass — report passing count + duration
- [ ] Code review: run `oh-my-claudecode:code-reviewer` — resolve all HIGH/CRITICAL issues before push
- [ ] PR description includes test results, fix rationale, and verification commands
- [ ] `README.md` and `README.ko.md` updated for every changed module
- [ ] KDoc added/updated for all new or modified public APIs
- [ ] Work was done inside a git worktree (`.worktrees/<branch>/`)

## Git Workflow

- **Base branch**: `develop`
- **Commits**: Korean + prefix (`feat: ...`, `fix: ...`, `docs: ...`, `refactor: ...`)
- **Worktree**: `git worktree add .worktrees/<branch> -b <branch>`
- **After merging PR**: `./bin/clean-branches` (if available) or `git branch -d <branch>`
