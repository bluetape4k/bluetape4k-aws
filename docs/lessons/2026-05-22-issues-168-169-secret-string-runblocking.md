# Issues #168 and #169 Secret Redaction Guardrails

## Context

Daily review reported two small gaps in the Exposed database work:

- `AwsSecretString` redaction was tested for fresh instances, but not after Java
  serialization round-trip.
- `AwsExposedAutoConfiguration` used `runBlocking(Dispatchers.IO)` in a Spring
  bean factory without an explicit lifecycle justification comment.

## Decision

Convert `AwsSecretString` from a Kotlin value class to a regular serializable
class with explicit value equality. Java serialization does not call value-class
`readResolve` because Kotlin mangles the method to `readResolve-impl`, so a
regular class is required to re-run validation after deserialization. Add
focused serialization tests and document why the Spring factory method bridges
to suspend registry creation with `runBlocking`.

## Outcome

The tests now prove that deserialized `AwsSecretString` values still reveal only
through `reveal()`, keep generated diagnostics redacted through `toString()`,
and reject a tampered blank value during `readResolve`. Equality uses a
constant-time byte comparison, and `hashCode()` returns a redacted constant
rather than a secret-derived hash. README docs now state that Java-serialized
bytes contain the raw secret. The Spring auto-configuration now carries the same
synchronous lifecycle justification already used in the Ktor plugin.

## Verification

- `./gradlew :bluetape4k-aws-exposed:test --tests "io.bluetape4k.aws.exposed.AwsExposedDatabaseFactoryTest.secret string serialization round-trip preserves redaction" :bluetape4k-aws-spring-boot:compileKotlin --no-daemon --max-workers=1`
- `./gradlew :bluetape4k-aws-exposed:test :bluetape4k-aws-spring-boot:test --no-daemon --continue --max-workers=1` (test bodies passed, Gradle test-results binary cleanup issue after Spring task)
- `./gradlew :bluetape4k-aws-spring-boot:cleanTest :bluetape4k-aws-spring-boot:test --no-daemon --max-workers=1`
- `./gradlew :bluetape4k-aws-exposed:test :bluetape4k-aws-spring-boot:compileKotlin --no-daemon --max-workers=1`

## Future Guard

When adding redacted value objects, include both fresh-instance redaction tests
and serialization/copy/logging boundary tests when the type implements
`Serializable`. Avoid Kotlin value classes for Java-serializable redacted
wrappers that must enforce invariants after deserialization. When using
`runBlocking` in production initialization code, leave the lifecycle reason next
to the bridge.
