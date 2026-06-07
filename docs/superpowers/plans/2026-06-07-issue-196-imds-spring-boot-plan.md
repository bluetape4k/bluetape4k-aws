# Issue #196 - Spring Boot IMDS Integration Plan

Date: 2026-06-07
Spec: `docs/superpowers/specs/2026-06-07-issue-196-imds-spring-boot-design.md`

## Execution Order

1. Add AWS SDK IMDS dependency aliases.
   - Add `aws2-imds = software.amazon.awssdk:imds`.
   - Add `compileOnly(libs.aws2.imds)` and `testImplementation(libs.aws2.imds)`
     to `aws-spring-boot`.

2. Add IMDS configuration model.
   - Create `ImdsProperties` under `io.bluetape4k.aws.spring.imds`.
   - Keep default behavior enabled but startup-passive.
   - Validate positive durations and non-negative retry count.

3. Add operations facade.
   - Create `ImdsOperations`.
   - Create `ImdsCoroutinesTemplate` backed by `Ec2MetadataAsyncClient`.
   - Use `withTimeout(properties.requestTimeout)` for every metadata request.
   - Use bluetape4k validation helpers for path validation.
   - Add helpers for instance id, availability zone, region, instance type,
     local IPv4, and IAM role names.

4. Add Spring Boot auto-configuration.
   - Create `ImdsAutoConfiguration`.
   - Guard on `Ec2MetadataAsyncClient` and `SdkAsyncHttpClient`.
   - Configure endpoint, endpoint mode, token TTL, retry policy, and optional
     shared async HTTP client.
   - Back off for existing `Ec2MetadataAsyncClient` or `ImdsOperations`.
   - Register in `AutoConfiguration.imports`.

5. Add tests.
   - Auto-configuration registration, disabled state, classpath guard, custom
     bean backoff, property binding, and no startup call behavior.
   - Template string/list conversion and timeout behavior.

6. Update documentation.
   - Root README and README.ko service/dependency/config/usage sections.
   - `aws-spring-boot/README.md` and `README.ko.md` feature/config/usage
     sections.

7. Review and verify.
   - Run focused IMDS tests.
   - Run full `:bluetape4k-aws-spring-boot:test`.
   - Run `:bluetape4k-aws-spring-boot:compileKotlin`.
   - Run dependencyInsight for `imds`.
   - Run `git diff --check`.
   - Write implementation review and lesson.

## Risk Controls

- Do not call `Ec2MetadataAsyncClient.get(...)` from auto-configuration.
- Do not expose IMDS security-credentials values through first-class helpers.
- Keep live EC2 behavior untested locally; cover SDK interaction through mock
  async clients and bounded futures.
- Keep new API package narrow so #200 can reuse the same naming model for Ktor
  without taking a dependency on Spring Boot types.

## Expected Touched Files

- `gradle/libs.versions.toml`
- `aws-spring-boot/build.gradle.kts`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/imds/*`
- `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/imds/*`
- `README.md`, `README.ko.md`
- `aws-spring-boot/README.md`, `aws-spring-boot/README.ko.md`
- `docs/review/*`
- `docs/lessons/*`

## Validation Commands

```bash
./gradlew :bluetape4k-aws-spring-boot:dependencyInsight --dependency imds --configuration compileClasspath
./gradlew :bluetape4k-aws-spring-boot:compileKotlin
./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.imds.*'
./gradlew :bluetape4k-aws-spring-boot:test
git diff --check
```

## Stop Condition

Stop when the code, docs, review artifacts, lesson, local verification, PR body,
PR review, and CI evidence all pass with `P0=0` and `P1=0`. Merge remains a
separate user-approved step after PR creation.
