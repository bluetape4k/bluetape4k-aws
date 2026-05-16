# Promote CHANGELOG.md [Unreleased] to [0.1.0]

**Date**: 2026-05-16
**Issue**: #101
**Branch**: docs/changelog-010-promotion

## Decision

Promoted `[Unreleased]` section to `[0.1.0] - 2026-05-16` before first Maven Central publish.

Key structural changes:
- Renamed `[Unreleased]` → `[0.1.0] - 2026-05-16`
- Added empty `[Unreleased]` section above for future work
- Added bug-fix entries from PR #113 (#98) and PR #114 (#99, #100) under `### Fixed`
- Renamed `### Planned` → `### 0.2.0 Roadmap` and updated issue links (#105, #106 added)

## Future Guidance

- Before every Maven Central publish, promote `[Unreleased]` to the release version.
- Bug fixes merged just before release should be added to the release section, not left in Unreleased.
