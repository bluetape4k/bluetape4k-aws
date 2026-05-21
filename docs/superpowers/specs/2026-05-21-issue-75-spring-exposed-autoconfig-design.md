# Issue #75 Spring Exposed Auto-Configuration Design

Date: 2026-05-21
Repository: `bluetape4k-aws`
Branch: `feat/issue-75-spring-exposed-autoconfig`
Issue: https://github.com/bluetape4k/bluetape4k-aws/issues/75

## Problem

`bluetape4k-aws-exposed` now provides the framework-neutral database
foundation from #74. Spring Boot users still need a thin adapter that binds
Spring configuration properties, creates an `AwsExposedDatabaseRegistry`, and
exposes the default Exposed `Database` plus `DataSource` so
`bluetape4k-exposed` Spring repository/transaction conventions can compose with
AWS-backed database settings.

## Current Evidence

- `docs/superpowers/plans/2026-05-14-awspring-gap-wip-plan.md` orders database
  work as `#74 -> #75 -> #76 -> #77 -> #82`.
- #75 depends on #74 and asks for Spring Boot 4 auto-configuration, default and
  named databases, explicit/secret-backed config, user-bean backoff, and README
  coverage.
- #74 added `AwsDatabaseProperties`, `AwsExposedDatabaseFactory`, and
  `AwsExposedDatabaseRegistry` in `:bluetape4k-aws-exposed`.
- `aws-spring-boot` already registers auto-configurations through
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- Existing Secrets Manager and Parameter Store support loads arbitrary prefixed
  keys into the Spring `Environment`; this can feed
  `bluetape4k.aws.exposed.*` without a second AWS client path.
- Spring Boot 4 official docs confirm that external auto-configurations should
  be listed in `AutoConfiguration.imports`, use `@AutoConfiguration`, and use
  conditional annotations for classpath/property/user-bean backoff.
- Context7 lookup was attempted and blocked by monthly quota exhaustion, so
  Spring Boot assumptions are grounded in official `docs.spring.io` docs plus
  compile/test verification.

## Constraints

- Keep AWS/Exposed database creation in `:bluetape4k-aws-exposed`; the Spring
  module must be an adapter, not a second database layer.
- No awspring JDBC compatibility, JPA/Hibernate, Flyway/Liquibase migration, or
  production AWS integration tests.
- No secrets in logs or generated diagnostics; password values stay inside
  `AwsSecretString`.
- Spring binding uses Spring-local bindable DTOs, then converts passwords to
  `AwsSecretString`; do not bind the framework-neutral value object directly.
- If compileOnly types appear in bean signatures, guard the auto-configuration
  with `@ConditionalOnClass(name = [...])`.
- Apply `@ConditionalOnProperty` to every auto-configuration phase class.
- Split bean-order phases when `@ConditionalOnBean` is required.
- Public API KDoc, PR, and commit text must be English.
- README updates must cover `README.md` and `README.ko.md`.

## Selected Design

Add Spring Boot Exposed support inside `:bluetape4k-aws-spring-boot`:

- `AwsExposedProperties`
  - `@ConfigurationProperties(prefix = "bluetape4k.aws.exposed")`
  - fields: `enabled`, `defaultDatabase`, `namedDatabases`
  - converts to `AwsDatabaseProperties`
- `AwsExposedAutoConfiguration`
  - creates default `AwsDatabaseSettingsResolver` when none exists
  - creates default `AwsExposedDatabaseFactory` when none exists
  - creates closeable `AwsExposedDatabaseRegistry` when none exists and
    `bluetape4k.aws.exposed.default-database.url` is configured
- `AwsExposedDefaultDatabaseAutoConfiguration`
  - separate ordered phase after registry creation
  - exposes default `AwsExposedDatabaseHandle`, `DataSource`, and Exposed
    `Database` beans when user beans do not already exist
  - uses `destroyMethod = ""` for handle-derived aliases so registry owns the
    pool lifecycle

Remote config is reused through existing environment loaders:

```yaml
bluetape4k:
  aws:
    secrets-manager:
      sources:
        - secret-id: app/database
          prefix: bluetape4k.aws.exposed.default-database
    exposed:
      default-database:
        driver-class-name: org.postgresql.Driver
        url: ${database.url}
        username: ${database.username}
        password: ${database.password}
```

The same shape works with Parameter Store by pointing its source prefix at
`bluetape4k.aws.exposed.default-database`.

## Rejected Options

- Create a Spring-specific database factory. Rejected because it duplicates #74
  lifecycle, validation, registry, and RDS IAM hooks.
- Fetch Secrets Manager or Parameter Store directly during registry bean
  creation. Rejected because `aws-spring-boot` already owns environment loading
  and refresh behavior; duplicating the AWS client path would increase lifecycle
  and logging risk.
- Expose named `Database` beans dynamically. Rejected for #75 because dynamic
  bean registration complicates the auto-configuration contract. Named handles
  remain available through `AwsExposedDatabaseRegistry`.

## Acceptance Criteria

- `ApplicationContextRunner` proves registry, factory, resolver, default
  `DataSource`, and default Exposed `Database` beans are registered from
  explicit H2 properties.
- Tests prove auto-configuration backs off when disabled and when user registry,
  factory, resolver, default `DataSource`, or default `Database` beans exist.
- Tests prove registry/database aliases are not created when the default
  database URL is absent, even if the module is on the classpath.
- Tests prove secret-backed config by injecting a Spring property source with the
  same keys existing Secrets Manager / Parameter Store loaders would publish.
- Tests prove named database properties bind into the registry.
- A transaction test proves the default Exposed `Database` works with Exposed
  JDBC transaction usage.
- README and Korean README document Spring Boot usage and remote config prefix
  wiring.

## Step 2-R Review Notes

Claude Code Opus advisor: attempted, but the local CLI reported usage credits
exhausted. Artifact: `.omx/artifacts/claude-issue-75-spec-review-20260521.md`.

| Priority | Finding | Decision |
|---|---|---|
| P1 | Registry creation would fail startup when `aws-exposed` is on the classpath but no default database URL is configured. | Accepted: registry bean requires `bluetape4k.aws.exposed.default-database.url`, and tests must cover the absent-URL no-op path. |
| P2 | Binding `AwsSecretString` may require a Spring converter depending on Binder value-class support. | Resolved by Spring-local bindable DTOs that convert to `AwsSecretString`; tests cover redaction and reveal behavior. |
| P2 | Dynamic named `Database` beans would be convenient but expands bean-registration complexity. | Rejected for #75: named handles remain available through `AwsExposedDatabaseRegistry`. |

Convergence: P0 = 0, P1 = 0 after the default URL activation condition was
added to the spec.
