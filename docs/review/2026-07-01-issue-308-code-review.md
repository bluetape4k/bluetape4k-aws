# Issue 308 Code Review

## Scope

- Java SDK v2 EventBridge client factories, request builders, sync/async helpers, and coroutine adapters.
- AWS Kotlin SDK EventBridge client factory, request builders, and native suspend helpers.
- README locale set and dependency catalog/build declarations.

## Findings

| Lens | Severity | Finding | Resolution |
|---|---:|---|---|
| API contract | P0 | None. Helpers keep one SDK request per call and return raw SDK responses. | Verified by mock invocation-count tests. |
| Partial failures | P0 | None. `PutEvents`, `PutTargets`, and `RemoveTargets` responses are not collapsed to Boolean results. | Verified by raw response identity tests and README/KDoc notes. |
| Validation | P0 | None. Blank required fields and EventBridge 10-item request limits are checked before SDK calls. | Verified by Java and AWS Kotlin request-support tests. |
| Lifecycle | P0 | None. Java clients follow the existing `ShutdownQueue` pattern; AWS Kotlin clients remain caller-owned unless created through `withEventBridgeClient`. | Verified by client construction tests and source inspection. |
| Cancellation | P0 | None. Java coroutine helpers use `await()` and do not catch cancellation. | Verified by cancellation propagation test. |
| Emulator | P1 | No `*EventBridgeEmulator*` smoke exists in this repository, so a Floci/LocalStack EventBridge live smoke was not claimed. | Recorded unsupported-emulator evidence from `rg` and file listing; mock/request tests cover core wrappers. |
| Documentation | P0 | None. Root and module README locale pairs list EventBridge coverage, runtime dependency, partial-failure handling, and non-goals. | Verified by `rg EventBridge/eventbridge/partial` over README locale set. |

## Verification Evidence

- `./gradlew :bluetape4k-aws-java:test --tests '*EventBridge*' :bluetape4k-aws-kotlin:test --tests '*EventBridge*' --no-configuration-cache` passed.
- `./gradlew :bluetape4k-aws-java:compileTestKotlin :bluetape4k-aws-kotlin:compileTestKotlin --warning-mode all` passed.
- `git diff --check` passed.
- Emulator probe evidence: `find aws-java/src/test aws-kotlin/src/test -name '*EventBridgeEmulator*' -o -name '*EventBridge*'` listed only request/client/mock tests, with no emulator smoke class.
