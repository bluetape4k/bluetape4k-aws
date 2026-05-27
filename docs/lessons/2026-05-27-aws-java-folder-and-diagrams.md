# AWS Java folder rename and diagram font refresh

## Context

The physical `aws/` module directory still looked like the old core module
name even though the public Gradle module and artifact are
`bluetape4k-aws-java`. README diagrams also carried stale Helvetica-rendered
Graphviz output.

## Decision

Rename the physical directory to `aws-java/`, keep the public Gradle module as
`:bluetape4k-aws-java`, and regenerate README diagram assets from DOT evidence
with `Architects Daughter` for node text and `Comic Mono` for edge labels.

## Outcome

The Java, Kotlin, and Exposed module READMEs now use current architecture,
flow, and sequence diagrams. Root README diagram and chart assets were
rerendered with the expected font families.

## Verification

- `./gradlew :bluetape4k-aws-java:compileKotlin :bluetape4k-aws-kotlin:compileKotlin :bluetape4k-aws-exposed:compileKotlin --no-daemon --max-workers=1`
- `xmllint --noout` on touched SVG assets.
- README PNG/SVG link existence check.
- Font scan confirmed no Helvetica/Arial/sans-Serif in root/current touched DOT
  or SVG diagram assets.

## Future Guidance

Do not rely on `fc-match` alone on macOS for Font Book-installed fonts. Confirm
the generated SVG `font-family` values and inspect rendered PNGs before
handoff.
