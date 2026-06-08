# Issue #229 Spec Review

## Scope

Reviewed `docs/superpowers/specs/2026-06-08-issue-229-s3-vectors-design.md`
against GitHub issue #229, official AWS S3 Vectors evidence, current
`aws-java`, `aws-spring-boot`, and `aws-ktor` patterns, and bluetape4k workflow
requirements.

## Inputs

- GitHub issue #229, updated on 2026-06-08.
- AWS SDK Java v2 API reference for `S3VectorsAsyncClient`.
- Amazon S3 Vectors user guide and API operations reference.
- Maven artifact probe for `software.amazon.awssdk:s3vectors:2.46.0`.
- Existing Access Grants Spring and Ktor implementations.
- Prior lessons requiring optional S3 Vector dependency boundaries.

## 7-Tier Findings

| Tier | Scope | P0 | P1 | P2 | P3 | Notes |
|---|---|---:|---:|---:|---:|---|
| 1 Security | AWS credentials, IAM namespace, endpoint override, unsupported emulator claims | 0 | 0 | 0 | 0 | Spec keeps AWS SDK auth ownership with callers/default providers and avoids local emulator claims. |
| 2 Ops/SRE | startup, shutdown, retries/timeouts, resource cleanup | 0 | 0 | 0 | 0 | Spec requires side-effect-free install, plugin-owned close, and normal AWS SDK timeout/retry configuration guidance. |
| 3 Structural impact | `aws-java`, `aws-spring-boot`, `aws-ktor`, version catalog | 0 | 0 | 0 | 0 | Spec adds one optional SDK alias and reuses the shared `aws-java` facade across adapters. |
| 4 Kotlin/API quality | coroutine facade, public API, Ktor plugin surface | 0 | 0 | 0 | 0 | Spec narrows the first public operation set and prevents duplicate Spring/Ktor facades by default. |
| 5 Testability/types | SDK doubles, Spring slice tests, Ktor route tests | 0 | 0 | 0 | 0 | Spec names missing-class, caller-owned, customizer, delegation, lifecycle, and route-level tests. |
| 6 Performance/stability | async client behavior, dependency footprint, service maturity | 0 | 0 | 0 | 0 | Spec documents credentials/endpoint discovery blocking risk and keeps the SDK dependency optional. |
| 7 Docs/release evidence | README locale set, dependency docs, evidence integrity | 0 | 0 | 0 | 0 | Spec requires English/Korean README updates and a lesson; no diagram is required unless README visuals change. |

## Gate Verdict

PASS.

- P0: 0
- P1: 0
- P2: 0
- P3: 0

The spec can proceed to planning because it now fixes the most likely boundary
risk: Spring Boot and Ktor must reuse the shared `aws-java` `S3VectorsOperations`
surface unless implementation proves a real package-boundary problem.

## Iteration 2 - Extension Naming Clarification

After inspecting the AWS SDK Java v2 `S3VectorsAsyncClient` bytecode, the spec
was clarified so low-level coroutine extensions use `*Suspend` names. This
avoids Kotlin member-method resolution conflicts with AWS SDK async methods that
already use names such as `listVectorBuckets` and return `CompletableFuture`.

- P0: 0
- P1: 0
- P2: 0
- P3: 0

## Evidence

- `gh issue view 229 --json body` confirmed the issue body includes the
  `s3vectors` SDK surface, optional dependency boundary, and test-double
  constraint.
- `curl -fsSI https://repo1.maven.org/maven2/software/amazon/awssdk/s3vectors/2.46.0/s3vectors-2.46.0.pom`
  returned HTTP 200.
- `gradle/libs.versions.toml` has the `aws2 = "2.46.0"` line and no current
  `aws2-s3vectors` alias.
- Existing `S3AccessGrantsAutoConfiguration` and `S3AccessGrantsKtorPlugin`
  provide the optional compile-only and lifecycle patterns.
- Prior lessons `2026-05-26-issue-203-ktor-s3-advanced.md` and
  `2026-05-27-issue-192-spring-s3-advanced.md` require S3 Vector to remain out
  of the default S3 API surface.
