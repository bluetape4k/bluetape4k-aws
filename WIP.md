# WIP - bluetape4k-aws

Snapshot: 2026-05-26 KST
Scope: open GitHub issues assigned to `debop`.
Open count: 5 issues.

## Current Direction

The `0.2.2` patch work is clear. Active implementation work has moved to the
`0.3.0` AWS integration line, with DynamoDB, LocalStack, Ktor, and Exposed
configuration follow-ups queued.

## Active Queue

| Priority | Issue | Milestone | Notes |
|---|---|---|---|
| P1 | [#180](https://github.com/bluetape4k/bluetape4k-aws/issues/180) wire aws-exposed settings through Spring Boot Secrets Manager and Parameter Store | 0.3.0 | Spring Boot Exposed configuration integration. |
| P1 | [#181](https://github.com/bluetape4k/bluetape4k-aws/issues/181) add Ktor AWS database settings plugin for exposed integration | 0.3.0 | Ktor counterpart to #180. |
| P2 | [#179](https://github.com/bluetape4k/bluetape4k-aws/issues/179) add aws-ktor DynamoDB integration | 0.3.0 | New Ktor DynamoDB integration surface. |
| P2 | [#182](https://github.com/bluetape4k/bluetape4k-aws/issues/182) stabilize SNS-to-SQS fanout LocalStack coverage | 0.3.0 | Test hardening for LocalStack fanout. |
| P2 | [#183](https://github.com/bluetape4k/bluetape4k-aws/issues/183) share DynamoDB Local Testcontainers launcher across AWS and downstream repos | 0.3.0 | Cross-repo test infrastructure reuse. |

## Open PRs

| PR | Branch | Notes |
|---|---|---|
| [#185](https://github.com/bluetape4k/bluetape4k-aws/pull/185) build: consume projects 1.9.2 BOM | `release/projects-1.9.2-bom` | Still open; no milestone set. |

## Recently Completed

- [#186](https://github.com/bluetape4k/bluetape4k-aws/issues/186) / [PR #187](https://github.com/bluetape4k/bluetape4k-aws/pull/187) adopted the JetBrains Exposed Gradle plugin through `catalog/2026-05-26-00`.

## Refresh Notes

- Verified with `gh` on 2026-05-26 KST.
- Keep `bluetape4k-*` issue and resolving PR milestones aligned.
