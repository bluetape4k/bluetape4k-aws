# Disabled Test Registry Pattern

**Date**: 2026-05-16
**Issue**: #104
**Branch**: feat/issue-104-disabled-registry

## Context

Tests annotated with `@Disabled` in `bluetape4k-aws` have historically lacked a
centralized record. This makes it hard to audit which tests are skipped, why, and
whether they are tracked in an issue.

## Decision

Create `docs/disabled-tests.md` as the canonical registry of all `@Disabled` tests.
Each entry records: module, file path, test name, scope (class/method), category,
tracking issue, and reason.

Enforce a format convention for `@Disabled` annotations:

```
@Disabled("#NNN — <one-sentence reason>")
```

## Categories Defined

- `unsupported-emulator` — service/API not implemented by LocalStack or floci
- `out-of-band-protocol` — flow requires input delivered outside the emulator
  (SMS token, email callback, webhook)

## Deferred

A CI check that rejects `@Disabled` annotations without a `#NNN — ` prefix was
originally planned. This is deferred to a follow-up item in issue #104. The format
is currently enforced by convention and PR review.

## Lesson

Establish the registry NOW rather than after tests accumulate. Retroactive audits
are more expensive than keeping the registry current at annotation time. The PR
description should remind reviewers to update `docs/disabled-tests.md` when
adding a new `@Disabled` annotation.
