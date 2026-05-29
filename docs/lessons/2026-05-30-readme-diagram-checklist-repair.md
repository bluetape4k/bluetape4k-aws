# README diagram checklist repair

## Context

The README diagram refresh was merged before the `bluetape4k-diagram` checklist
was applied strictly to every rendered PNG. A follow-up audit inspected all
unique README diagram assets one by one and found failures across the full
batch.

## Decision

Regenerate the README diagram batch with the checklist treated as a gate:

- final README assets use the standard title/subtitle/frame/chip/footer shell
- final service/database endpoint shapes use standard cards instead of
  cylinder strokes that cross title text
- flow and lifecycle diagrams stay vertical
- connector endpoints are orthogonal at node boundaries
- non-endpoint connector lanes keep visible clearance from boxes
- box text blocks are vertically middle aligned
- PNG/SVG/Graphviz evidence stays beside every README asset

## Source Drift Evidence

The regenerated diagrams were checked against the current checked-out
repository structure and README scope on 2026-05-30.

- Root repository diagrams: `README.md`, `README.ko.md`, `settings.gradle.kts`,
  root module directories, and current README feature/module sections.
- `aws-exposed`: `aws-exposed/README.md`, `aws-exposed/README.ko.md`, current
  Exposed configuration, registry, factory, and transaction API documentation.
- `aws-java`: `aws-java/README.md`, `aws-java/README.ko.md`, current Java SDK
  wrapper README sections for builders, async clients, coroutine adapters, and
  lifecycle ownership.
- `aws-kotlin`: `aws-kotlin/README.md`, `aws-kotlin/README.ko.md`, current
  Kotlin SDK wrapper README sections for `withXClient`, `clientOf`, Flow, and
  short-lived client lifecycle.
- `aws-ktor`: `aws-ktor/README.md`, `aws-ktor/README.ko.md`, current Ktor plugin
  and advanced S3/SQS README sections.
- `aws-spring-boot`: `aws-spring-boot/README.md`,
  `aws-spring-boot/README.ko.md`, current Spring Boot auto-configuration README
  sections.
- `bom`: `bom/README.md`, `bom/README.ko.md`, and the BOM platform role.
- Examples: all `examples/*/README.md` and `examples/*/README.ko.md` files for
  Ktor/Spring Boot DynamoDB, Exposed, S3, SQS/SNS flows.

## Outcome

All README-visible diagram assets must be re-audited after generation with the
full checklist. A contact sheet is useful for triage only; final evidence must
come from per-diagram rendered PNG inspection and targeted SVG/Graphviz/source
checks.

The first follow-up still over-trusted generated output. The visible failures
were connector stubs that did not meet box boundaries at 90 degrees, connector
lanes that visually crowded boxes, and footer text whose two-line block was not
vertically centered. Increasing the canvas and spacing is preferable to forcing
awkward connector detours into a cramped layout.

## Verification Evidence

Final verification on 2026-05-30 covered all 32 unique README-visible PNG
assets.

- README image references: `readmes=31`, `unique=32`, `missing=0`,
  `svgEmbed=0`, `nonPng=0`, `c3Missing=0`.
- Endpoint routing gate: `files=64`, `totalEdges=189`, `bad=0`.
- Connector clearance gate: `files=64`, `segments=478`, `bad=0`.
- SVG well-formedness: `xmllint --noout` passed for README diagram SVG files.
- Whitespace validation: `git diff --check` passed.
- Visual spot checks after the routing gate covered the reported failure cases:
  root overview footer vertical centering, Java SDK API routing, KMS dashed
  route and label placement, component map bottom routing, Ktor SQS custom
  route, and vertical aws-java/aws-kotlin/aws-exposed flows.
- Root diagrams 1-9: C1-C12 passed in per-diagram visual audit.
- Module and BOM diagrams 10-18 and 24: C1-C12 passed; Graphviz evidence files
  were verified beside each PNG.
- Framework diagrams 19-23: C1-C12 passed after rerouting connector endpoints
  and increasing the advanced S3 architecture canvas height.
- Example diagrams 25-32: C1-C12 passed; Graphviz evidence files were verified
  beside each PNG.

## Future Rule

Do not merge or report completion for README diagram batches until the
checklist result has one row per unique README-visible PNG and no mandatory
item remains failed or missing.

Do not treat a generated diagram as visually correct just because the source
script ran. For connector-heavy diagrams, run deterministic endpoint and
clearance gates first, then open the rendered PNGs that changed or previously
failed. If a connector can only pass by taking an ugly detour, enlarge the
diagram canvas or increase node spacing before accepting the route.
