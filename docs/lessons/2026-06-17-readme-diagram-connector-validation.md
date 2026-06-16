# README Diagram Connector Validation

## Context

During the S3 README diagram refresh, visual review missed a detached connector
endpoint in `bluetape4k-aws-s3-components-24.svg`.

## Decision

README diagram validation must include machine checks for arrow endpoint
attachment in addition to card containment, card overlap, XML validity, PNG
rendering, and manual PNG inspection.

## Rule

Before reporting a module diagram as ready for review:

1. Run `xmllint --noout` on every touched SVG.
2. Render every touched SVG to PNG with CairoSVG.
3. Inspect every rendered PNG.
4. Run `node docs/diagram-validation/validate-readme-diagram-svg.mjs <touched-svg...>`.
5. Reject the diagram if any arrow start or end is detached from a card, layer,
   or lane boundary.

## Outcome

The S3 component map was fixed by reconnecting the `Body helpers` arrow to the
`List objects` card edge, and the reusable validator now fails detached
connectors explicitly.
