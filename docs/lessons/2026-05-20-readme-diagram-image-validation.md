# README Diagram Image Validation

## Context

README Mermaid diagrams were replaced with generated PNG embeds while keeping
the generated SVG and README-facing PNG files under
`docs/images/readme-diagrams`.

## Decision

Use the shared pastel infographic renderer for architecture, class, and sequence
diagrams. Diagram text stays English-only, PNG is the README-facing artifact,
and SVG remains available for future regeneration or inspection.

Large diagram titles are fitted to the available width before rendering so
module subtitles do not clip in root README assets.

## Outcome

The AWS README diagram set was regenerated with content-sized canvases:

- 30 rendered artifacts
- 15 PNG files
- 15 SVG source files
- no missing README image links
- no local SVG image embeds in README files
- no remaining Mermaid code blocks

## Verification

- `node /Users/debop/work/bluetape4k/.omx/scripts/refine-readme-diagrams.mjs .`
- README image link and Mermaid residue checker
- PNG/SVG shape checker
- Visual sample sheet review
- `git diff --check`

## Future Guidance

When regenerating README diagrams, inspect a contact sheet before PR creation.
Architecture diagrams should use content-driven dimensions, class diagrams need
visible inheritance/use arrows, and sequence diagrams must not be forced into a
fixed height.
