# Issue #75 Spring Exposed Auto-Configuration

## Context

Issue #75 adds the Spring Boot adapter for the framework-neutral
`bluetape4k-aws-exposed` registry from #74.

## Decision

Keep database creation in `:bluetape4k-aws-exposed`. In
`:bluetape4k-aws-spring-boot`, bind Spring-local DTOs under
`bluetape4k.aws.exposed`, convert them to `AwsDatabaseProperties`, and expose
default `AwsExposedDatabaseHandle`, `DataSource`, and Exposed `Database` aliases
only after a registry exists.

Default Spring Boot AWS emulator tests now use Floci through a shared
`AwsSpringBootTestEmulator` helper. LocalStack remains only as an explicit
fallback value for `-Dbluetape4k.aws.emulator=localstack`.

## Outcome

The adapter supports explicit H2 properties, named database binding, secret or
parameter-backed Spring property sources, user-bean backoff, absent default URL
no-op startup, and Exposed JDBC transaction usage.

## Verification

- `./gradlew :bluetape4k-aws-spring-boot:cleanTest :bluetape4k-aws-spring-boot:test --no-build-cache --no-configuration-cache --no-daemon` — 116 passing with default Floci emulator.
- `./gradlew :bluetape4k-aws-spring-boot:compileTestKotlin :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.exposed.AwsExposedAutoConfigurationTest' --tests 'io.bluetape4k.aws.spring.kms.KmsCoroutinesEncryptorAwsEmulatorTest' --tests 'io.bluetape4k.aws.spring.sns.SnsCoroutinesTemplateAwsEmulatorTest' --no-build-cache --no-configuration-cache --no-daemon` — 21 passing.
- `git diff --check` — clean.

## Future Guard

Do not bind framework-neutral secret value objects directly with Spring Binder
when a simple Spring-local DTO can preserve binding behavior and convert to the
shared model after validation. For AWS emulator tests in `aws-spring-boot`, use
the shared helper instead of direct `LocalStackServer.Launcher` calls.
