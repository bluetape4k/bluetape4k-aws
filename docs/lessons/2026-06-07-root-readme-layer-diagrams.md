# Root README layer diagrams

## Context

The root README service coverage chart and architecture diagrams were behind
the current module surface. The chart omitted RDS IAM, Secrets Manager, and
Parameter Store paths now exposed through `aws-exposed`, `aws-spring-boot`, and
`aws-ktor`.

## Decision

Regenerate the root README service chart and architecture diagrams as shared
English-label SVG/PNG assets under `docs/images/readme-diagrams`. Architecture
diagrams use explicit layer bands with a left label gutter and color-coded
routes instead of Mermaid.

## Outcome

The root README and Korean README keep the same asset paths while the images
now show current service coverage and layered module boundaries. README service
lists now include RDS IAM, Secrets Manager, and Parameter Store.

## Verification

- Regenerated SVG, PNG, DOT, plain, and sketch assets for the service coverage
  chart and three architecture diagrams.
- Geometry gate reported `badEndpointAngle=0`, `badBends=0`, and
  `interiorCrossings=0` for all regenerated diagrams.
- Visually inspected the service chart, runtime architecture, and combined
  contact sheet for spacing, label overlap, and route readability.

## Future Guidance

Reserve a left label gutter for layer titles and short subtitles before placing
components. If labels or routes start to crowd, increase the canvas or remove
inline connector labels and use color semantics plus a footer legend.
