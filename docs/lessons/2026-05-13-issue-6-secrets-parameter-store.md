# Issue #6 Secrets Manager / Parameter Store Lesson

## Context

Issue #6 adds Secrets Manager and SSM Parameter Store as Spring Environment
sources in `aws-spring-boot`.

## Decision

Use Spring Boot 4 `org.springframework.boot.EnvironmentPostProcessor` registered
through `META-INF/spring.factories`, not normal auto-configuration beans, because
remote values must be available before `@ConfigurationProperties` binding.

## Outcome

- Added startup-time Environment source loading for configured Secrets Manager
  and Parameter Store sources.
- Added optional lazy refresh through `refresh-interval`; reload failures keep
  the previously loaded values.
- Added `@SecretsValue` and `@ParameterStoreValue` as composed annotations over
  Spring `@Value`, preserving normal placeholder semantics.
- Kept AWS service SDK dependencies as `compileOnly`.
- Split post-processors from SDK-backed loaders so `spring.factories` can load
  post-processor classes without resolving service SDK types unless sources are
  configured.

## Verification

- `./gradlew :aws-spring-boot:compileKotlin`
- `./gradlew :aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.secretsmanager.*' --tests 'io.bluetape4k.aws.spring.parameterstore.*'` — 4 passing
- `./gradlew :aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.env.AwsEnvironmentPropertySourceSupportTest' --tests 'io.bluetape4k.aws.spring.secretsmanager.SecretsValueTest' --tests 'io.bluetape4k.aws.spring.parameterstore.ParameterStoreValueTest'` — 5 passing
- `./gradlew :aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.secretsmanager.*' --tests 'io.bluetape4k.aws.spring.parameterstore.*' --tests 'io.bluetape4k.aws.spring.env.AwsEnvironmentPropertySourceSupportTest' -Dbluetape4k.aws.emulator=localstack` — 11 passing
- `./gradlew :aws-spring-boot:test -Dbluetape4k.aws.emulator=localstack` — 85 passing

## Future Guard

For Environment source integrations, do not put compileOnly service SDK types
directly in classes loaded from `spring.factories`. Keep those classes small,
guard classpath presence first, then delegate to SDK-backed loaders.
For refreshable sources, reload lazily inside the `PropertySource` instead of
adding a scheduler; this keeps the startup contract simple and avoids hidden
threads in Spring Environment infrastructure.
