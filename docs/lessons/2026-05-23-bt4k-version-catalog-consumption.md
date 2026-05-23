# bt4k Version Catalog Consumption

## Context

`bluetape4k-aws` had local version pins for shared dependencies already present
in the `bluetape4k-dependencies` published version catalog.

## Decision

Import the shared catalog as `bt4k` and resolve shared leaf dependency versions
through `bt4kVersion(alias)` in dependency management. Leave plugin and BOM
train versions local where the build still depends on local plugin aliases.

## Outcome

The selected shared dependency aliases are versionless in the local catalog, and
the actual versions come from `bluetape4k-dependencies`.

## Verification

- `git diff --check`
- `./gradlew help --no-daemon --no-configuration-cache`
- `./gradlew compileKotlin --no-daemon --no-configuration-cache`

## Future Guidance

When adding shared AWS-related or common dependencies, add the version to
`bluetape4k-dependencies` first and consume it from `bt4k` here.
