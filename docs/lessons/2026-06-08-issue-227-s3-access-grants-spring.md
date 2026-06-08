# Issue #227 Spring S3 Access Grants

Date: 2026-06-08
Issue: #227

## Context

Issue #192 deliberately deferred S3 Access Grants because it is not part of the
ordinary S3 SDK surface. Current AWS SDK Java v2 exposes Access Grants through
the S3 Control service module, `software.amazon.awssdk:s3control`.

## Decision

- Add Access Grants as a separate Spring Boot opt-in under
  `bluetape4k.aws.s3.access-grants`.
- Keep `s3control` as an optional service dependency: `compileOnly` for
  production and `testImplementation` for tests.
- Register `S3ControlClient`, `S3ControlAsyncClient`, and
  `S3AccessGrantsOperations` only when the S3 parent integration is enabled and
  `bluetape4k.aws.s3.access-grants.enabled=true`.
- Reuse shared AWS client defaults and global/service customizers with service
  name `s3control`.
- Keep the coroutine operations surface focused on read/data-access methods;
  administrative create, update, and delete methods remain available through raw
  S3 Control clients.

## Outcome

`aws-spring-boot` now provides optional S3 Access Grants auto-configuration and
a coroutine template for `getDataAccess`, `listCallerAccessGrants`,
`listAccessGrants`, `listAccessGrantsInstances`, and
`listAccessGrantsLocations`. README English/Korean files document the runtime
dependency, opt-in property, and Spring injection example.

## Verification

- `./gradlew :bluetape4k-aws-spring-boot:dependencyInsight --dependency s3control --configuration compileClasspath`
  showed `software.amazon.awssdk:s3control:2.46.0` on compile classpath.
- `./gradlew :bluetape4k-aws-spring-boot:compileKotlin --no-daemon --max-workers=1`
  passed.
- `./gradlew :bluetape4k-aws-spring-boot:compileTestKotlin --no-daemon --max-workers=1`
  passed.
- `./gradlew :bluetape4k-aws-spring-boot:test --tests '*S3AccessGrants*' --no-daemon --max-workers=1`
  passed with 14 focused tests.

## Future Guard

Do not fold S3 Access Grants into `S3Operations`; it belongs to S3 Control and
requires a distinct optional runtime dependency. When adding more Access Grants
methods, keep administrative operations explicit and preserve raw client escape
hatches for account-management workflows.
