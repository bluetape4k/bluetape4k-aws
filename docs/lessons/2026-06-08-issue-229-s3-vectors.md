# Issue #229 S3 Vectors optional boundary

## Context

Issue #229 added optional Amazon S3 Vectors support across `aws-java`,
`aws-spring-boot`, and `aws-ktor`.

## Decision

Keep S3 Vectors separate from the ordinary S3 object API because AWS exposes it
through the separate `s3vectors` SDK service and IAM namespace. Reuse the
`aws-java` coroutine facade from Spring Boot and Ktor instead of creating
adapter-specific operation interfaces.

## Outcome

- `software.amazon.awssdk:s3vectors` stays `compileOnly` plus test scope in the
  library modules.
- Consumers add `runtimeOnly("software.amazon.awssdk:s3vectors")` only when they
  enable or install S3 Vectors support.
- Spring Boot activation requires `bluetape4k.aws.s3-vectors.enabled=true`.
- Ktor activation requires explicit `S3VectorsKtorPlugin` installation.
- README diagrams and service coverage prose must reflect S3 Vectors as
  optional and must not imply emulator support.
- README diagram revisions must preserve the existing pastel card/badge
  decoration language. When route density or labels crowd, enlarge the canvas
  and use semantic route colors instead of collapsing the image into a
  different visual shape.

## Verification

- Maven Central artifact probe confirmed `software.amazon.awssdk:s3vectors`.
- `javap` confirmed `S3VectorsAsyncClient` operation names before API wrapping.
- Focused Gradle compile/test runs covered `aws-java`, `aws-spring-boot`, and
  `aws-ktor` S3 Vectors paths.
- PR review follow-up regenerated the root README component map and service
  coverage chart through `tools/generate-root-readme-diagrams.py`, including
  DOT/plain/sketch evidence, final SVG/PNG assets, geometry-gate summaries,
  font scans, XML parsing, and rendered PNG inspection.
- Diagram re-review changed the component map connectors from multi-segment
  orthogonal routes to direct straight routes, centered service coverage badges
  inside each matrix cell, and made the coverage sketch PNG render the actual
  matrix instead of a placeholder label.

## Future Rule

When AWS introduces a new service-specific SDK artifact, start with an optional
facade in the lowest shared module, reuse it in framework adapters, and document
runtime dependency ownership clearly in all README locale files.

For root README diagram updates, keep the existing card/badge decoration unless
the user explicitly requests a redesign. Relationship-heavy component maps need
free-style or layered placement, semantic connector colors, and generator-level
geometry proof before PNG preview.
If a reviewer asks for straight routes, validate them as explicit
boundary-to-boundary connectors and still check non-endpoint card intersections.
