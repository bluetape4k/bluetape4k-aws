# README source-backed visuals

## Context

The root README module table did not list every example from
`settings.gradle.kts`, and generated overview labels drifted to mixed
`Ktor` casing.

## Decision

Treat `settings.gradle.kts` as the source of truth for root module/example
coverage and keep generated visual labels aligned with actual module names.

## Outcome

The README table now includes all current AWS Ktor and Spring Boot examples.
Root overview labels use `aws-ktor-*`, and the component map uses smaller arrow
heads with orthogonal routing.

## Verification

- `git diff --check`
- `xmllint --noout` for changed SVG assets
- `rsvg-convert` PNG rendering
- README image-link existence scan

## Next

When adding examples, update `settings.gradle.kts`, the root README table, and
the root visual assets together.
