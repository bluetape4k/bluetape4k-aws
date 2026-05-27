# Post 0.3.0 Next Development Line

## Context

AWS 0.3.0 was published to Maven Central and GitHub Releases. The repository
needed to reopen development on the next patch line without changing public
README snippets that should continue to show the latest stable release.

## Decision

Set `baseVersion=0.3.1` and keep `snapshotVersion=` empty. Snapshot publication
continues to inject `-PsnapshotVersion=-SNAPSHOT` from the workflow.

## Outcome

Future development snapshots resolve as `0.3.1-SNAPSHOT`, while README install
examples still point users at stable `0.3.0` artifacts.

## Verification

- `./gradlew help --refresh-dependencies --no-daemon --no-configuration-cache --no-build-cache`
- `git diff --check`

## Future Guard

After each stable release, advance `baseVersion` to the next patch development
line in a separate PR before starting feature work.
