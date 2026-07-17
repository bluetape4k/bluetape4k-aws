# Central Release POM Metadata

## Context

The 0.1.0 Central Portal release failed validation because generated Maven POMs
omitted dependency version metadata for dependencies managed by imported BOMs.

## Decision

Keep Spring dependency-management POM customization enabled for release POMs so
the generated POM includes dependency management entries.

## Outcome

Generated publication POMs now include `dependencyManagement` with
`io.github.bluetape4k:bluetape4k-bom:1.8.0` and no `SNAPSHOT` references.

## Verification

- `./gradlew generatePomFileForBluetapeAwsPublication --no-daemon --no-configuration-cache --no-build-cache`
- Searched generated `pom-default.xml` files for `SNAPSHOT`.

## Future Guidance

Before tagging a Central release, generate Maven POMs locally and verify managed
dependencies are represented either with explicit versions or valid POM
dependency management.

## 2026-07-17 Follow-up

A repository-wide snapshot audit found that this rule must be executable rather
than a manual release check. Published BOM imports now use versioned central
`bt4k` aliases, and `scripts/publication/validate_poms.rb` checks every
generated POM both structurally and through Maven effective-model construction.
Regular dependencies may remain versionless only when Maven can resolve them
through the same POM's dependency management or a versioned imported BOM.
