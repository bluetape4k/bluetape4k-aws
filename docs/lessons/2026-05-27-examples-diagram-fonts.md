# 2026-05-27 - Example README Diagram Fonts

## Context

The example module README diagrams still used Helvetica/Arial-era font stacks
after the root and module diagrams were updated to the current diagram style.

## Decision

Use `Architects Daughter` for prominent component text and `Comic Mono` for
edge labels and detail text across `examples-*` README diagram assets. Remove
legacy Helvetica, Arial, and Comic Sans fallback stacks from the touched SVG/DOT
sources so font drift is visible in text checks.

## Outcome

All example README architecture PNGs were regenerated from updated SVG/DOT
sources. Graphviz-backed diagrams keep the same layout and now emit the expected
font families.

## Verification

- Checked `docs/images/readme-diagrams/examples-*` SVG/DOT assets for legacy
  font strings.
- Ran `xmllint --noout` for all touched example SVG files.
- Rendered all example PNG assets with `rsvg-convert`.
- Reviewed a contact sheet at `.omx/artifacts/examples-diagram-font-contact.png`.

## Future Guidance

When README diagram font feedback lands, scan every asset family by prefix, not
only the diagrams touched in the most recent PR.
