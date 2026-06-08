# Issue #228 Ktor S3 Access Grants Plan

Date: 2026-06-08
Issue: #228

## Classification

Type B Fast Track. The change adds a focused optional integration to an
existing module and follows an existing Ktor plugin/runtime pattern.

## Steps

1. Issue intake and source review
   - Update #228 with current #227 context.
   - Read `AwsKtorCore`, CloudWatch/IMDS plugin patterns, S3 Ktor docs, and
     Spring Access Grants implementation.

2. Dependency and shared defaults
   - Add `aws2.s3control` to `aws-ktor` as `compileOnly` and
     `testImplementation`.
   - Add an `AwsKtorS3ControlAsyncClientCustomizer` lane to `AwsKtorDefaults`
     and `AwsKtorCoreConfig`.

3. Ktor Access Grants API
   - Add `S3AccessGrantsKtorOperations`.
   - Add `S3AccessGrantsKtorTemplate`.
   - Add `S3AccessGrantsKtorRuntime`.
   - Add `S3AccessGrantsKtorPluginConfig`.
   - Add `S3AccessGrantsKtorPlugin` plus application accessors.

4. Tests
   - Plugin stores injected operations.
   - Disabled plugin stores no operations and accessor fails.
   - Injected client remains application-owned.
   - Plugin-owned client closes once.
   - Shared customizer runs before service customizer.
   - Template delegates all exposed methods to `S3ControlAsyncClient`.

5. README and lesson
   - Update `aws-ktor/README.md` and `aws-ktor/README.ko.md`.
   - Add a short `docs/lessons` entry.

6. Validation
   - `./gradlew :bluetape4k-aws-ktor:compileKotlin --no-daemon --max-workers=1`
   - `./gradlew :bluetape4k-aws-ktor:compileTestKotlin --no-daemon --max-workers=1`
   - `./gradlew :bluetape4k-aws-ktor:test --tests '*S3AccessGrants*' --no-daemon --max-workers=1`
   - related defaults/plugin regression tests if touched behavior warrants it
   - `git diff --check`

7. Review and PR
   - Create tracked 7-tier review artifact with `P0=0`, `P1=0`.
   - Commit with Lore protocol.
   - Create PR with `--body-file`, verify final `##` section is
     `## DoD Status`.
   - Run PR review gate before CI gate.

## Risks

- S3 Control SDK types are compile-only in production, so public signatures must
  keep the dependency optional but still require consumers to provide the
  runtime dependency.
- Mocking AWS SDK async clients must not hide lifecycle ownership behavior.
- Access Grants is not emulator-backed; tests should cover delegation and Ktor
  lifecycle, not claim live AWS behavior.
