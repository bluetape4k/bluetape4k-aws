# Diagram Orthogonal Connector Pass

## Context

Several README diagram SVGs rendered correctly but still used diagonal connector
segments, mismatched arrow marker colors, or validator-invisible layer classes.
These defects are easy to miss when checking only XML validity or PNG export.

## Decision

README diagram connectors should use horizontal, vertical, or rounded bent paths
by default. Diagonal connector segments need an explicit source or style reason,
and marker colors must match the connector stroke color.

## Outcome

The AWS README diagram set was updated to replace diagonal edge routes with
orthogonal rounded paths, align marker units and marker colors, and make layered
cards visible to the repo diagram validator.

## Verification

- Rendered every changed SVG to PNG.
- Visually inspected each changed PNG and a final contact sheet.
- Ran XML validation for all diagram SVG files.
- Ran the repo README diagram validator for all diagram SVG files.
- Checked for remaining diagonal edge segments, static SVG hazards, marker color
  parity, README image references, and whitespace errors.

## Future Guidance

When editing AWS diagrams, do not treat successful rendering as enough. Run the
same visual and static checks, and justify any remaining diagonal connector
segment in the completion report.
