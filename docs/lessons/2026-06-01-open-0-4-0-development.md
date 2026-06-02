# 2026-06-01 Open 0.4.0 Development

## Context

`bluetape4k-aws` `0.3.1` was published for release-train dependency alignment.

## Decision

Move the committed `baseVersion` to `0.4.0` while keeping `snapshotVersion=`
empty so release workflows can inject snapshot qualifiers explicitly.
Align direct bluetape4k BOM references to the next catalog-train snapshots:
`bluetape4k-bom:1.11.0-SNAPSHOT` and
`bluetape4k-exposed-bom:1.11.0-SNAPSHOT`.

## Outcome

The repository is ready for the next minor development line.

## Verification

- `gradle.properties` uses `baseVersion=0.4.0`.
- `snapshotVersion=` remains empty.
- `./gradlew help --no-daemon --console=plain` resolves the updated catalog.
