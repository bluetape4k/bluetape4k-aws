# Issue #180 Spring Exposed Settings Resolver Review

Date: 2026-07-01
Scope: `aws-spring-boot` Exposed auto-configuration, Spring Environment-backed database settings resolver, README locale set.

## Verdict

P0: 0
P1: 0

## Review Notes

| Tier | Result | Evidence |
|---|---|---|
| Tier 4 correctness | PASS | `SpringEnvironmentAwsDatabaseSettingsResolver` overlays only existing keys under `secretSource` / `parameterSource` prefixes and leaves unset fields unchanged. Required missing sources fail fast; optional missing sources keep explicit settings. |
| Tier 5 integration | PASS | `AwsExposedAutoConfiguration` now creates the registry when either `default-database.url` or a configured source descriptor prefix exists. It still backs off when neither URL nor source descriptor exists. |
| Tier 7 docs/evidence | PASS | `README.md` and `README.ko.md` document the Spring Environment source flow and clarify that Exposed does not create an additional AWS client path. |
| Security / secret handling | PASS | Remote `password` values are wrapped in `AwsSecretString`; tests assert reveal behavior without logging raw values. |
| Regression | PASS | `:bluetape4k-aws-spring-boot:test` passed with 247 tests, 0 failures, 0 errors, 0 skipped. |
| Graph impact | PASS | CodeGraph affected-flow check reported 0 affected flows for the touched files; this repository graph does not model the Spring auto-configuration runtime path. |

## Validation

- `./gradlew :bluetape4k-aws-spring-boot:compileTestKotlin --warning-mode all --no-daemon --stacktrace`: PASS.
- `./gradlew :bluetape4k-aws-spring-boot:test --no-daemon --stacktrace`: PASS.
- Test XML summary: `tests=247 failures=0 errors=0 skipped=0`.
- `git diff --check`: PASS.

## Residual Risk

The resolver intentionally depends on Spring Environment property names that are already published by the existing Secrets Manager and Parameter Store post-processors. It does not fetch AWS values directly, so applications must still configure the corresponding Environment source.
