# Module And Example Diagram Style Refresh

## Context

The `aws-exposed`, `aws-java`, `aws-kotlin`, BOM, and example README diagrams
still looked like direct Graphviz renders. Several operation flow and lifecycle
sequence diagrams were too wide and shallow, making them harder to read in
GitHub README view.

## Decision

Regenerate the module, BOM, and example README diagrams with the same pastel
card-style visual language as the root README refresh. Use vertical layouts for
operation flows, client lifecycle sequences, exposed configuration flow, and the
BOM architecture.

## Outcome

The refreshed assets keep existing README image paths while adding in-image
titles, subtitles, top chips, framed canvas, centered cards, clear connectors,
and role footers. Graphviz DOT/plain/sketch evidence was generated for the
updated assets, including examples that previously had only final SVG/PNG.

## Verification

- Rendered all updated SVG assets to PNG with `rsvg-convert`.
- Rendered matching Graphviz sketch PNG files.
- Inspected `.omx/artifacts/module-example-diagram-redesign-contact.png`.
- `xmllint --noout` passed for README diagram SVG assets.
- README image-link check passed with `missing=0` and `local_svg_embeds=0`.
- SVG/PNG pair check passed with `png_pairs_missing=0`.
- `git diff --check` passed.

## Future Guidance

Do not publish direct Graphviz-style module or example README diagrams as final
assets. Prefer vertical layouts for process, flow, and lifecycle diagrams; use
wide layouts only when the content is a true component map.
