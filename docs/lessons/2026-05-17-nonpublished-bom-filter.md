# Non-published module BOM filter

## Context

The release workflow should not manage examples, demos, or benchmarks through
consumer BOM constraints.

## Decision

`bluetape4k-aws-bom` now filters non-published modules by normalized project
path and artifact name before adding constraints.

## Outcome

Future example/demo/benchmark modules will stay out of the AWS BOM without
needing per-module exclusions.

## Verification

- `./gradlew generatePomFileForBluetapeAwsPublication --no-daemon --no-configuration-cache --no-build-cache`
- Generated BOM POM scan found no `examples`, `demo`, or `benchmark` entries.
