# AWS 0.3.0 Release Prep

## Context

The AWS 0.3.0 milestone closed the S3/SQS production-hardening slice for Spring
Boot and Ktor, including advanced examples and documentation refresh work.

## Decision

Prepare the release tag with `baseVersion=0.3.0`, `snapshotVersion=`, README
dependency snippets at `0.3.0`, and upstream BOM imports pinned to
`bluetape4k-bom:1.9.2` plus `bluetape4k-exposed-bom:1.9.2`.

## Outcome

Release metadata and public install snippets now point at the immutable 0.3.0
line. Stable publication still requires a fresh Nightly(full), snapshot
validation, release tag, and release workflow dispatch from the merged prep
state.

## Verification

- `./gradlew help --refresh-dependencies --no-daemon --no-configuration-cache --no-build-cache`
- `./gradlew clean generatePomFileForBluetapeAwsPublication --no-daemon --no-configuration-cache --no-build-cache`
- Generated POM scan found no `SNAPSHOT`, example, demo, or benchmark artifact
  leakage.
- Generated POMs contain `0.3.0` for AWS artifacts and `1.9.2` for the upstream
  bluetape4k and Exposed BOM imports.
- `./gradlew publishToMavenLocal -x collectReachabilityMetadata --no-daemon --no-configuration-cache --no-build-cache -Dorg.gradle.parallel=false`
- `./gradlew build -x test -x koverVerify -x collectReachabilityMetadata --no-daemon --no-configuration-cache --no-build-cache -Dorg.gradle.parallel=false`

## Future Guard

Do not create the `0.3.0` tag until the release-prep PR is merged and the
current `develop` SHA has fresh Nightly(full) and snapshot publication evidence.
The full combined `build publishToMavenLocal` path can still hit Gradle's
GraalVM reachability metadata exclusive-lock guard when `org.gradle.parallel`
is enabled, so keep release-prep compile and publish checks separated or disable
parallel execution.
