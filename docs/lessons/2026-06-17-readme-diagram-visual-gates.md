# README Diagram Visual Gates

## Context

The README diagram refresh repeatedly passed basic SVG validation while still
leaving reader-visible quality issues: AWS icons were added by service-name
matching instead of semantic service ownership, icon cards were not resized
after adding the artwork, and orthogonal connectors sometimes entered cards
parallel to the card edge.

## Decision or Finding

README diagram review needs a visual gate after machine validation. The SVG
validator proves containment, overlap, font, and endpoint attachment, but it
does not prove semantic icon use, card interior spacing, or connector entry
angle.

## Outcome

The AWS Java module diagrams were corrected by limiting AWS service icons to
actual service target cards, increasing service-card and flow-card height where
text needed room, and rerouting architecture connectors so orthogonal lines
enter target cards perpendicularly.

## Verification

- `xmllint --noout` on touched AWS Java SVG files.
- `node docs/diagram-validation/validate-readme-diagram-svg.mjs` on touched AWS
  Java SVG files.
- CairoSVG PNG rendering for touched diagrams.
- Contact-sheet and focused PNG visual inspection.
- `git diff --check`.

## Future Guidance

- Do not add AWS icons by keyword matching. Add them only when the card denotes
  an actual AWS service or resource target.
- After adding an icon, resize the card and reposition text before accepting the
  diagram.
- For straight or orthogonal connectors, reject routes whose final segment
  touches a card edge in parallel. The connector should enter the edge
  perpendicularly unless the target is a layer/lane boundary.
- Treat validator PASS as necessary but not sufficient; finish with a visual
  sweep of icon semantics, text fit, connector entry angle, labels, and margins.
