# AWS 0.2.0 Release Prep

## Context

The AWS 0.2.0 milestone closed the Exposed database foundation, Spring Boot and
Ktor adapters, RDS IAM auth, Exposed examples, and SES sender issues. The release
depends on `bluetape4k-projects` 1.9.0 and `bluetape4k-exposed` 1.9.0.

## Decision

Prepare the release tag with `baseVersion=0.2.0`, `snapshotVersion=`, and the
version catalog pinned to `bluetape4k-bom:1.9.0` plus
`bluetape4k-exposed-bom:1.9.0`.

## Outcome

The generated publication metadata publishes immutable `io.github.bluetape4k.aws`
0.2.0 artifacts and imports immutable upstream BOMs. The WIP queue now reflects
that no assigned open issues remain.

## Verification

- `./gradlew properties --no-configuration-cache --no-daemon --quiet`
- `./gradlew clean generatePomFileForBluetapeAwsPublication --no-daemon --no-configuration-cache --no-build-cache`
- Generated POM scan for `SNAPSHOT|examples|demo|benchmark`.
- Generated POM scan for `bluetape4k-bom:1.9.0`, `bluetape4k-exposed-bom:1.9.0`,
  and artifact version `0.2.0`.
- `actionlint .github/workflows/release.yml .github/workflows/publish-snapshot.yml .github/workflows/nightly-tests.yml .github/workflows/ci.yml`
- `./gradlew build -x test -x koverVerify publishToMavenLocal --no-daemon --no-configuration-cache --no-build-cache`

## Future Guard

Do not tag AWS 0.2.0 until Exposed 1.9.0 is visible on Maven Central. Downstream
release trains must not consume snapshot upstream BOMs. When the gate opens,
retry compile/publish verification without `--parallel` if GraalVM metadata
tasks hit Gradle's exclusive-lock guard.
