# Issue 359 aws-kotlin STS KDoc Review

- Scope: aws-kotlin auth/http/sts KDoc English migration, plus CI coverage artifact validation repair discovered while verifying the PR.
- Diff shape: 7 Kotlin files, comment text only; no imports, signatures, or implementation lines changed. Workflow changes are limited to coverage artifact validation and download-name normalization.
- Korean scan: 0 files with Korean text under aws-kotlin auth/http/sts after the change.
- Validation: compileKotlin and dokkaGenerateModuleHtml passed for :bluetape4k-aws-kotlin; Dokka emitted pre-existing unresolved-link warnings in Kinesis/SesV2 files outside this slice. PR CI exposed a coverage artifact validation mismatch for partial module runs; actionlint, git diff --check, and a local shell simulation passed after the workflow repair.
- P0/P1: none found.
