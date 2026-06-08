# Issue 294 Code Patterns Preflight

## Context

The initial cleanup scope was too narrow. The user clarified that the listed examples were only signals, and the actual goal was repo-wide `bluetape4k-code-patterns` compliance plus stronger bluetape4k ecosystem reuse before 0.4.0.

## Decision

- Keep the full workflow gate: spec review P0/P1=0, then plan review P0/P1=0, then implementation.
- Treat data class serialization, coroutine blocking boundaries, raw assertions, and ecosystem helper reuse as separate scan lanes.
- Use `bluetape4k-jdbc` where the replacement is exact (`hikariDataSourceOf`), but do not force the RDS IAM `DriverManager` custom `DataSource` into a risky abstraction in the same PR.

## Outcome

- `AwsSecretString` construction now goes through guarded factories.
- Published production and touched test data classes now implement `Serializable` with `serialVersionUID`.
- Ktor close paths and selected blocking calls use `runInterruptible(Dispatchers.IO)`.
- Raw assertion imports in touched/high-signal tests were replaced with `bluetape4k-assertions`.
- Repo-wide Kotlin source `!!` scan now returns 0 results; AWS SDK nullable response tests use `shouldNotBeNull()`.
- Follow-up #295 tracks the remaining RDS IAM JDBC abstraction.

## Verification

- Published module compile: PASS.
- Exposed/Kotlin/Spring targeted tests: PASS.
- Full `aws-kotlin:test`: PASS, 489 passing + 12 pending.
- Ktor targeted suite: PASS, 150 tests.
- Static scans: data class missing marker 0, raw assertion imports 0, `!!` 0, nested `withContext(IO)+runInterruptible` 0.

## Future Rule

For broad pre-release cleanup, do not stop at the user's examples. Build scan lanes from the skill rules, fix safe P0/P1/high-confidence P2 items, and create follow-up issues for behavior-changing ecosystem abstractions.
