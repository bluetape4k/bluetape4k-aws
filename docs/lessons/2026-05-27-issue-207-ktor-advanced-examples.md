# Issue 207 Ktor advanced examples

## Context

Issue #207 asked for Ktor-native examples that make the advanced S3 and SQS
features usable without copying Spring examples.

## Decision

Extend the existing `aws-ktor-s3-examples` and `aws-ktor-sqs-examples` modules
instead of adding a new example module. This keeps CI and README entry points
small while proving the new advanced APIs from #203 and #199.

## Outcome

- S3 examples now cover content-type detection, S3-backed config objects, and a
  local in-memory data-key provider for client-side encryption demos.
- SQS examples now run in manual acknowledgement mode and expose retry-once,
  interceptor, and observer evidence through Ktor routes.
- Module READMEs and `aws-ktor` README files link the advanced examples.

## Verification

Pending in this branch: run targeted example tests and local review before PR.

## Future Guard

When adding Ktor example coverage for AWSpring parity, prefer extending the
existing Ktor example modules first. Keep unsupported AWS-only scenarios clearly
documented rather than adding non-runnable examples.
