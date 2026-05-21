# Issue #75 Spring Exposed Auto-Configuration Plan

Date: 2026-05-21
Repository: `bluetape4k-aws`
Branch: `feat/issue-75-spring-exposed-autoconfig`
Issue: https://github.com/bluetape4k/bluetape4k-aws/issues/75

## Scope

Implement the Spring Boot adapter for `:bluetape4k-aws-exposed` inside
`:bluetape4k-aws-spring-boot`.

## Tasks

1. Build dependencies
   - Add `compileOnly(project(":bluetape4k-aws-exposed"))`.
   - Add test dependencies needed for H2-backed auto-configuration tests.

2. Spring API
   - Add `AwsExposedProperties` under `io.bluetape4k.aws.spring.exposed`.
   - Add `AwsExposedAutoConfiguration` for resolver/factory/registry beans.
   - Guard registry creation with
     `bluetape4k.aws.exposed.default-database.url` so classpath presence alone
     does not fail application startup.
   - Add an `AwsSecretString` converter only if Binder tests prove a converter
     is required.
   - Add `AwsExposedDefaultDatabaseAutoConfiguration` for default handle,
     `DataSource`, and Exposed `Database` aliases.
   - Register both auto-configuration phases in `AutoConfiguration.imports`.

3. Tests
   - Add `AwsExposedAutoConfigurationTest` with `ApplicationContextRunner`.
   - Cover explicit H2 properties, disabled property, classpath backoff,
     absent default URL no-op behavior, resolver/factory/registry/user default
     bean backoff, named database binding, secret-backed property-source
     binding, and Exposed transaction usage.

4. Documentation
   - Update `aws-spring-boot/README.md`.
   - Update `aws-spring-boot/README.ko.md`.
   - Mention dependency on `bluetape4k-aws-exposed` and the
     `bluetape4k.aws.exposed` property prefix.

5. Verification
   - Run targeted compile and tests for `:bluetape4k-aws-spring-boot`.
   - Run `git diff --check`.
   - Run current-session code review and Claude CLI advisor review when
     available; record any advisor gap.

6. Delivery
   - Add `docs/lessons/2026-05-21-issue-75-spring-exposed-autoconfig.md`.
   - Commit with Lore trailers.
   - Push branch and create PR assigned to `debop` with labels
     `aws-spring-boot`, `spring-boot`, `exposed`, and `database` when present.
   - Check PR CI and stop before merge.

## Risks

- Spring configuration binding for `AwsSecretString` may need a converter. The
  first implementation should prove binder behavior with tests and add a narrow
  converter only if required.
- Registry creation calls a suspend factory from Spring bean initialization. Use
  a tightly scoped `runBlocking(Dispatchers.IO)` boundary and keep cancellation
  concerns out of long-running loops.
- A default `DataSource` bean alias must not double-close the pool; registry
  owns lifecycle.

## Step 3-R Review Notes

Claude Code Opus advisor: not rerun separately because the Step 2-R attempt
reported exhausted local usage credits. Artifact:
`.omx/artifacts/claude-issue-75-spec-review-20260521.md`.

| Priority | Area | Finding | Required plan edit |
|---|---|---|---|
| P1 | Startup | Plan did not prevent classpath-only startup failure when no default DB URL exists. | Added registry URL guard and absent-URL no-op test task. |
| P2 | Binding | `AwsSecretString` value-class binding may fail silently if not tested. | Added binder test and converter fallback task. |
| P2 | Lifecycle | Alias beans for handle-derived `DataSource`/`Database` must not own pool close. | Plan keeps registry as lifecycle owner and uses no-destroy aliases. |

Convergence: P0 = 0, P1 = 0 after adding the URL guard and no-config test.
