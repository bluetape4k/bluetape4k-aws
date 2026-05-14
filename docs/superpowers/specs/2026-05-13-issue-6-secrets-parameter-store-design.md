# Issue #6 Secrets Manager / Parameter Store Design

## Context

- Repository: `bluetape4k-aws`
- Issue: <https://github.com/bluetape4k/bluetape4k-aws/issues/6>
- Target module: `aws-spring-boot`
- Work type: new Spring Boot feature, Full Design lane

Issue #6 asks for AWS Secrets Manager and SSM Parameter Store integration as
Spring `Environment` sources, without awspring. This must happen before normal
bean binding when users want remote values to participate in
`@ConfigurationProperties`.

## Evidence

- Current `aws-spring-boot` auto-configuration registers service clients through
  `AutoConfiguration.imports`, string `@ConditionalOnClass`, service-specific
  `@ConfigurationProperties`, and `compileOnly` AWS SDK service dependencies.
- Spring Boot 4.0.3 documentation keeps `EnvironmentPostProcessor`
  registration through `META-INF/spring.factories` for early Environment
  mutation.
- AWS SDK Java v2 exposes `SecretsManagerClient.getSecretValue` with
  `GetSecretValueRequest.secretId` and `GetSecretValueResponse.secretString`.
- AWS SDK Java v2 exposes SSM `SsmClient.getParameter` and
  `SsmClient.getParametersByPath`; SSM parameter values are read from
  `Parameter.value`.
- qmd has no issue #6-specific prior design artifact; current implementation
  evidence comes from repo source plus official Spring/AWS documentation.

## Goals

1. Add AWS Secrets Manager and SSM SDK aliases and `compileOnly` dependencies.
2. Add environment post-processors that load configured remote sources before
   bean creation.
3. Add typed properties for Secrets Manager and Parameter Store source lists.
4. Add AWS SDK clients only when the relevant SDK module is present.
5. Add optional lazy refresh for configured sources.
6. Add composed `@SecretsValue` and `@ParameterStoreValue` annotations over
   Spring `@Value`.
7. Add ApplicationContextRunner and LocalStack tests for source loading and
   refresh.
8. Update `README.md` and `README.ko.md`.

## Non-Goals

- No awspring or Spring Cloud dependency.
- No runtime refresh scheduler. Startup-time loading remains the durable Spring
  Boot contract; optional refresh is lazy on property access and keeps previous
  values if reload fails.
- No binary Secrets Manager value support.
- No cross-account AssumeRole helper.

## Configuration Model

Secrets Manager prefix: `bluetape4k.aws.secrets-manager`

- `enabled: Boolean = true`
- `region: String? = null`
- `endpointOverride: URI? = null`
- `failFast: Boolean = true`
- `refreshInterval: Duration? = null`
- `sources: List<Source> = emptyList()`

Secret source:

- `name: String? = null`
- `secretId: String`
- `prefix: String? = null`
- `optional: Boolean = false`
- `format: SecretFormat = JSON`

Secret formats:

- `JSON`: parse a JSON object into property keys.
- `TEXT`: expose the full secret string at `prefix` or source `name`.

Parameter Store prefix: `bluetape4k.aws.parameter-store`

- `enabled: Boolean = true`
- `region: String? = null`
- `endpointOverride: URI? = null`
- `failFast: Boolean = true`
- `refreshInterval: Duration? = null`
- `sources: List<Source> = emptyList()`

Parameter source:

- `name: String? = null`
- `path: String`
- `prefix: String? = null`
- `recursive: Boolean = true`
- `withDecryption: Boolean = true`
- `optional: Boolean = false`

Validation:

- `endpointOverride` requires `region`.
- Source names and prefixes must not be blank when present.
- Secret sources require non-blank `secretId`.
- Parameter sources require a non-blank absolute path starting with `/`.
- Remote lookup is skipped when a feature is disabled or has no sources.
- `refreshInterval`, when present, must be positive.

## Property Mapping

Secrets Manager:

- `JSON` secrets are flattened using dot notation.
- `TEXT` secrets require either `prefix` or `name`; the full secret string is
  assigned to that key.
- `prefix` is prepended to all generated keys.

Parameter Store:

- `getParametersByPath` is paged until `nextToken` is empty.
- Each parameter name has the configured source path stripped.
- Remaining path segments are converted to dot-separated property keys.
- `prefix` is prepended when configured.

Property source order:

- Add remote property sources after command-line arguments when present, else at
  the beginning of the `MutablePropertySources`.
- Later configured sources should not unexpectedly override earlier configured
  sources with the same key; each source remains a separate named property
  source and Spring property source order controls resolution.

## Startup And Failure Behavior

- If SDK classes are missing while sources are configured and enabled, fail with
  an actionable `IllegalStateException`.
- If an optional source cannot be loaded, skip it.
- If `failFast=false`, log and skip failing sources.
- AWS clients are created inside the post-processor and closed immediately after
  loading sources.
- Post-processors use synchronous AWS SDK clients because Spring Environment
  mutation is a blocking startup phase.
- When `refreshInterval` is set, a refreshable `PropertySource` reloads lazily
  after the interval has elapsed. Successful reloads replace the in-memory map;
  skipped or failed reloads keep the previous map.

## Tests

ApplicationContextRunner tests:

- No remote lookup when no sources are configured.
- Missing SDK classes fail only when sources are configured.
- Endpoint override without region fails binding.
- JSON secret values become Environment properties.
- Text secret requires an explicit key.
- Parameter path values become Environment properties.
- Disabled feature skips remote lookup.
- Optional source skips missing remote value.

LocalStack tests:

- Create a secret, load it as JSON, and bind it into the Environment.
- Create SSM parameters under a path, load them recursively, and bind them into
  the Environment.
- Update a LocalStack secret/parameter and verify the refreshable property
  source observes the new value after `refreshInterval`.

## README Updates

Update both `README.md` and `README.ko.md`:

- Add `software.amazon.awssdk:secretsmanager` and `software.amazon.awssdk:ssm`
  runtime dependencies for `aws-spring-boot`.
- Add Secrets Manager and Parameter Store configuration snippets.
- Show `@ConfigurationProperties` and composed value annotations consuming
  remotely loaded values.

## Acceptance Criteria

- `aws-spring-boot` compiles with Secrets Manager and SSM SDKs as `compileOnly`.
- Environment post-processors are registered in `META-INF/spring.factories`.
- No AWS lookup happens by default when no sources are configured.
- `refresh-interval` reloads configured sources lazily while preserving previous
  values on reload failure.
- `@SecretsValue` and `@ParameterStoreValue` resolve normal Spring placeholders.
- Public API KDoc is English.
- Targeted issue #6 tests pass.
- `./gradlew :aws-spring-boot:test` passes.
