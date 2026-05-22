# 2026-05-22 AWS 0.1.1 Patch Release

Context: `develop` had already moved on to the 0.2.0 feature line, but the
0.1.1 milestone contained only the S3 pagination and versioned bucket cleanup
fixes. Tagging `develop` as 0.1.1 would have mixed 0.2.0 features into a patch
release.

Decision: Prepare `0.1.1` from the patch-line commit `d6389fd`, after issues
`#145` and `#147`, and keep `develop` unchanged for the 0.2.0 line. Align the
patch release with `bluetape4k-bom` 1.9.0 and keep `snapshotVersion=` empty for
the release tag.

Outcome: `0.1.1` can be released as a focused S3 patch while the Exposed/RDS/SES
feature work remains on `develop` for the later `0.2.0` release.

Verification: Check Gradle project version, scan for SNAPSHOT release
references, generate release publication POMs, then monitor the release workflow
after tagging.

Future guard: When `develop` has advanced past a patch milestone, release the
patch from the milestone commit instead of retagging the current feature line.
