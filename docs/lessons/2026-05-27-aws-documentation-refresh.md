# AWS documentation refresh

## Context

The repository documentation had stale README examples, old snapshot dependency
versions, and README diagrams that were hard to maintain because several image
assets had no DOT/PLAIN layout evidence.

## Decision

Refresh user-facing README content from the current Gradle module layout and
replace stale README diagrams with Graphviz-backed SVG/PNG assets. Keep README
embeds on PNG files, and store DOT, PLAIN, and sketch SVG evidence beside the
rendered assets.

## Outcome

- Root README module tables now include Ktor/Spring Exposed examples and current
  Ktor S3/SQS scenario descriptions.
- Dependency snippets now use the current `baseVersion` line.
- KMS PlantUML blocks were replaced with rendered README images.
- Ktor S3/SQS and new Exposed/Spring SQS example diagrams now have Graphviz
  sources and rendered PNG/SVG assets.

## Verification

- Local README image-link check: 62 image links, 0 missing.
- SVG parse check: `xmllint --noout` passed for README diagram SVG assets.
- Diagram audit: P1=0 after relabeling low-signal S3 diagram text.

## Future Guidance

When adding or updating README diagrams, add the DOT source and PLAIN layout
evidence in the same change. Do not reintroduce inline Mermaid or PlantUML
blocks for README architecture content.
