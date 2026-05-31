# Issue #244/#245 - Shared Ktor Module Adoption

## Context

Issues #244 and #245 asked the AWS Ktor module and examples to prefer the
shared `bluetape4k-ktor-*` module family where it already owns generic Ktor
behavior.

## Decision

Adopted `bluetape4k-ktor-core` and `bluetape4k-ktor-testing` in the Ktor-facing
AWS modules and examples, but kept direct Ktor artifacts where the dependency
is still an explicit runtime or serialization choice:

- `ktor-client-cio`, `ktor-server-cio`, and AWS service clients remain
  application/runtime choices.
- `ktor-serialization-jackson` and content-negotiation client dependencies
  remain intentional for examples whose DTOs use Jackson rather than kotlinx
  serialization.
- Ktor `MockEngine` behavior remains explicit in S3 tests because those tests
  verify AWS/S3 request shapes.

## Outcome

`aws-ktor` now exposes the shared Ktor core baseline, examples use shared route
parameter helpers where applicable, and tests use the shared Ktor response
assertion surface.

## Verification

- `./gradlew :bluetape4k-aws-ktor:compileTestKotlin :aws-ktor-dynamodb-examples:compileTestKotlin :aws-ktor-exposed-examples:compileTestKotlin :aws-ktor-s3-examples:compileTestKotlin :aws-ktor-sqs-examples:compileTestKotlin`
- `./gradlew :bluetape4k-aws-ktor:test :aws-ktor-dynamodb-examples:test :aws-ktor-exposed-examples:test :aws-ktor-s3-examples:test :aws-ktor-sqs-examples:test --max-workers=1`
- `./gradlew :bluetape4k-aws-ktor:dependencyInsight --configuration compileClasspath --dependency bluetape4k-ktor-core`
- `git diff --check`
- Local 7-tier review: no P0/P1 findings. Main reviewed risks were Jackson vs
  kotlinx serialization boundaries, transitive Ktor server dependencies, and
  route parameter behavior.

## Future Guard

Do not replace Jackson-based example content negotiation with
`bluetape4k-ktor-core`'s kotlinx JSON installer unless the DTOs are migrated to
kotlinx serialization and the route/test behavior is reverified.
