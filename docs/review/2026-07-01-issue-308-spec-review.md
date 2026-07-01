# Issue #308 Spec Review

## Scope

- Spec: `docs/superpowers/specs/2026-07-01-issue-308-eventbridge-core-design.md`
- Gate: Step 2-R
- Mode: native lanes plus main-session integration; security lane timed out and
  was replaced by main-session fallback after user correction.

## Findings

| Tier | P0 | P1 | P2/P3 | Disposition |
|---|---:|---:|---|---|
| Performance | 0 | 1 | PutTargets wording, stress evidence, allocation notes | P1 fixed by adding PutEvents 10-entry and 1 MB contract; P2/P3 folded into plan tasks. |
| Stability | 0 | 2 | Cancellation and delete ordering notes | P1 fixed by adding client lifecycle and PutEvents limit contracts; P2 folded into KDoc/test requirements. |
| Developer/API | 0 | 2 | Target/delete/KDoc/Kotlin builder scope notes | P1 fixed by adding PutEvents and PutRule contracts; lower items folded into plan tasks. |
| Operator/Ops | 0 | 1 | Observability, partial failure, emulator backend notes | P1 fixed by adding Floci-first smoke probe or unsupported evidence requirement. |
| User/Caller | 0 | 2 | Module README and unsupported edge capability notes | P1 fixed by adding partial-failure and public KDoc/README requirements. |
| Security fallback | 0 | 0 | Caller-controlled detail/target/resource validation reviewed | No blocking finding after spec revisions. |

## Integrated Verdict

- P0: 0
- P1: 0 after spec revisions
- Remaining P2/P3: accepted into the implementation plan as concrete tests,
  KDoc, README, and emulator-probe tasks.

