# WIP - bluetape4k-aws

Snapshot: 2026-05-22 KST
Scope: open GitHub issues assigned to `debop`.
Open count: 0 issues.

## Refresh Notes

Verified with `gh` on 2026-05-22 KST.

- The 0.1.1 patch release lane closed S3 pagination and versioned bucket force-delete work.
- The 0.2.0 feature lane closed the AWS Exposed foundation, framework adapters,
  examples, RDS IAM auth, and SES sender work.
- No assigned open issues remain in this repository at this snapshot.

## Current Direction

The 0.1.x patch lane and 0.2.0 API/foundation lane are closed. The next queue
should be selected from new issues after the 0.2.0 release is visible on Maven
Central and downstream repositories have consumed it.

## Priority Queue

| Priority | Issue | Difficulty | Notes |
|---|---|---:|---|
| - | - | - | No assigned open issues. |

## Dependency Map

No active dependency map remains. Preserve the completed ordering as release
history:

```text
#145 -> #147 -> 0.1.1 patch release
#74 -> #75/#76 -> #77 -> #82, plus #7 -> 0.2.0 feature release
```

## WIP Limits

| Lane | Limit | Current next |
|---|---:|---|
| Repository maintenance | 1 | Wait for new assigned issues after the 0.2.0 release. |
