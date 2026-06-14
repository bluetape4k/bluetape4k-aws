# AWS README Diagram And Chart

## Context

The 2026 `bluetape4k_part4-2_aws.pptx` deck explains the AWS Java SDK v2 bridge,
AWS Kotlin SDK suspend helpers, Spring Boot operations, Ktor integration, and
service coverage. The README already had architecture diagrams, but the module
table lacked a concise component map and service coverage chart.

## Decision

Add two pastel README assets under `docs/images/readme-diagrams/`:

- `bluetape4k-aws-components-04.{svg,png}` for module/component composition.
- `bluetape4k-aws-service-coverage-chart-05.{svg,png}` for service coverage by module.

Keep SVG source files next to PNG outputs and place both images after the module
table in `README.md` and `README.ko.md`.

## Outcome

The README module section now shows both the integration shape and the service
coverage matrix before the detailed architecture diagrams.

## Verification

- SVG XML parsed successfully.
- PNG files rendered with `rsvg-convert` at 1200x720.
- README local image links resolved with no missing files.

## Future Note

When adding more AWS README visuals, keep the same pastel near-white frame,
Architects Daughter-style section labels, and SVG+PNG asset pair convention.
For component maps, verify arrows against the actual module dependency story:
Kotlin services use both `aws-java` and `aws-kotlin`, Spring Boot uses
`aws-java` and `aws-spring-boot`, and Ktor uses `aws-kotlin` and `aws-ktor`.
The physical source directory can remain `aws/`; the public Gradle module and
artifact-facing label is `bluetape4k-aws-java`.
