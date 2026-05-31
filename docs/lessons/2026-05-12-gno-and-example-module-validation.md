# GNO and example module validation

Date: 2026-05-12

## Context

Issues #33, #34, #12, and #15 combined KDoc maintenance with new Spring Boot and
Ktor S3 example modules.

## Decision

Use `GNO` first for existing design-plan discovery, then fall back to direct
file inspection when the current worktree is not indexed or when `gno query`
tries to download a local reranking model.

## Outcome

`GNO` found the existing S3 design notes quickly for Spring Boot, but a broader
Ktor query attempted a large model download. Direct file inspection was faster
for the already-known example module surface.

## Verification

Validated with:

```bash
./gradlew :aws-spring-boot:compileKotlin :aws-ktor:compileKotlin :aws-spring-boot-s3-examples:test :aws-ktor-s3-examples:test
./gradlew :aws-ktor-s3-examples:test
./gradlew detekt
```

## Future Guidance

Prefer `gno search` or `gno query --no-rerank` for lightweight lookup during
implementation. Use `gno get` for known artifacts. Avoid broad reranked queries
inside tight edit-test loops unless the extra retrieval quality is needed.
