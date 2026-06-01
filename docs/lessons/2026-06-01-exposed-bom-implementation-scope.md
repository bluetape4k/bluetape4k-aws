# Exposed BOM implementation scope

## Context

The `bluetape4k-dependencies 1.2.0` release train promotes
`bluetape4k-exposed-bom` to `1.10.0`. `aws-exposed` needs the aligned Exposed
helper line but should not publish the bluetape4k Exposed BOM platform as an
API dependency.

## Decision

Keep `libs.bluetape4k.exposed.bom` on `implementation(platform(...))` in
`aws-exposed` and align the catalog version to `1.10.0`.

## Outcome

The module can compile and test against the promoted Exposed helper line while
keeping the BOM platform out of API scope for consumers.

## Verification

- Maven Central returned HTTP 200 for `bluetape4k-exposed-bom:1.10.0`.
- `./gradlew :bluetape4k-aws-exposed:build --no-daemon --console=plain`
  passed.

## Future Guidance

Do not re-promote `bluetape4k-exposed-bom` to `api(platform(...))`; expose only
the concrete Exposed API artifacts that are part of the public contract.
