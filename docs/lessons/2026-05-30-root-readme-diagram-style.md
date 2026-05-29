# Root README Diagram Style Refresh

## Context

Root README assets used plain Graphviz-style diagrams without visible in-image
titles, subtitles, frame, top chips, or role footer. They no longer matched the
reviewed bluetape4k README diagram style used by current module overview
assets.

## Decision

Regenerate the root README diagram set as pastel card-style infographics while
keeping the existing PNG embed paths and matching SVG sources. Keep English
labels in images and preserve README prose localization.

## Outcome

Nine root README visuals now include title, subtitle, framed canvas, compact
semantic chips, centered card text, and a bottom role band:

- overview and module composition
- component map and service coverage
- three architecture diagrams
- KMS components and encrypt/decrypt flow

## Verification

- Rendered all updated SVG files to PNG with `rsvg-convert`.
- Rendered missing Graphviz sketch PNG evidence for the root README assets.
- Inspected a contact sheet at `.omx/artifacts/root-readme-redesign-contact.png`
  and checked the two initially crowded diagrams individually.
- `xmllint --noout` passed for root README SVG assets.
- README image-link check passed with `missing=0` and `local_svg_embeds=0`.
- SVG/PNG pair check passed with `png_pairs_missing=0`.
- `git diff --check` passed.

## Future Guidance

Root README diagrams should follow the same card-style language as the approved
module overview samples: title/subtitle at the top, pastel cards, clear
connector stems, English labels, and a short role footer. Do not publish plain
Graphviz-rendered assets directly as final README diagrams.
