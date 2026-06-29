# Core Secrets Manager and Parameter Store Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add framework-neutral Secrets Manager and SSM Parameter Store helpers to `bluetape4k-aws-java` and `bluetape4k-aws-kotlin`.

**Architecture:** Follow existing service wrapper patterns. Java SDK v2 gets sync, async, and coroutine adapters; AWS Kotlin SDK gets native suspend helpers. Secret-bearing values use module-local redacted value objects and raw binary payload helpers stay on raw SDK calls.

**Tech Stack:** Kotlin 2.4, Java 21/25-compatible Gradle modules, AWS Java SDK v2 `secretsmanager`/`ssm`, AWS Kotlin SDK `secretsmanager`/`ssm`, MockK, JUnit 5, bluetape4k-assertions.

**Execution note:** Commit this spec and plan before implementation starts. Implementation commits must remain separate from the planning artifact commit.

---

## File Map

- Modify: `gradle/libs.versions.toml`
  - Add `aws-kotlin-secretsmanager` and `aws-kotlin-ssm` aliases.
- Modify: `aws-java/build.gradle.kts`
  - Add `compileOnly` and `testImplementation` for `libs.aws2.secretsmanager` and `libs.aws2.ssm`.
- Modify: `aws-kotlin/build.gradle.kts`
  - Add `compileOnly` and `testImplementation` for `libs.aws.kotlin.secretsmanager` and `libs.aws.kotlin.ssm`.
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/secretsmanager/AwsSecretValue.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/secretsmanager/SecretsManagerClientSupport.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/secretsmanager/SecretsManagerAsyncClientSupport.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/secretsmanager/SecretsManagerClientExtensions.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/secretsmanager/SecretsManagerAsyncClientExtensions.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/secretsmanager/SecretsManagerAsyncClientCoroutinesExtensions.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/secretsmanager/model/SecretsManagerRequestSupport.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/ssm/SsmClientSupport.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/ssm/SsmAsyncClientSupport.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/ssm/SsmClientExtensions.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/ssm/SsmAsyncClientExtensions.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/ssm/SsmAsyncClientCoroutinesExtensions.kt`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/ssm/model/SsmRequestSupport.kt`
- Create: `aws-java/src/test/kotlin/io/bluetape4k/aws/secretsmanager/AwsSecretValueTest.kt`
- Create: `aws-java/src/test/kotlin/io/bluetape4k/aws/secretsmanager/SecretsManagerSupportTest.kt`
- Create: `aws-java/src/test/kotlin/io/bluetape4k/aws/ssm/SsmSupportTest.kt`
- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/secretsmanager/AwsSecretValue.kt`
- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/secretsmanager/SecretsManagerClientSupport.kt`
- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/secretsmanager/SecretsManagerClientExtensions.kt`
- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/secretsmanager/model/SecretsManagerRequestSupport.kt`
- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/ssm/SsmClientSupport.kt`
- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/ssm/SsmClientExtensions.kt`
- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/ssm/model/SsmRequestSupport.kt`
- Create: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/secretsmanager/AwsSecretValueTest.kt`
- Create: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/secretsmanager/SecretsManagerClientSupportTest.kt`
- Create: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/ssm/SsmClientSupportTest.kt`
- Modify: `README.md`, `README.ko.md`, `aws-java/README.md`, `aws-java/README.ko.md`, `aws-kotlin/README.md`, `aws-kotlin/README.ko.md`
- Modify: `docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.svg`
- Regenerate: `docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.png`

## Task 1: Dependencies And Redacted Values

**Complexity:** medium

**Applies:** `$bluetape4k-code-patterns`, `$ecc-kotlin-testing`

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `aws-java/build.gradle.kts`
- Modify: `aws-kotlin/build.gradle.kts`
- Create: `aws-java/src/main/kotlin/io/bluetape4k/aws/secretsmanager/AwsSecretValue.kt`
- Create: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/secretsmanager/AwsSecretValue.kt`
- Create tests listed in the file map.

- [ ] **Step 1: Write failing redaction tests**

Create tests that assert:

- blank values throw `IllegalArgumentException`
- `reveal()` returns raw value
- `toString()` is `"****"`
- equality works for equal raw values and does not expose raw values
- `hashCode()` equals the redacted marker hash
- sentinel raw value is absent from exception messages and string rendering

Run:

```bash
./gradlew :bluetape4k-aws-java:test --tests '*AwsSecretValueTest' :bluetape4k-aws-kotlin:test --tests '*AwsSecretValueTest' --no-configuration-cache
```

Expected: FAIL because classes do not exist.

- [ ] **Step 2: Add dependency aliases and declarations**

Add to `gradle/libs.versions.toml`:

```toml
aws-kotlin-secretsmanager = { module = "aws.sdk.kotlin:secretsmanager", version.ref = "aws-kotlin" }
aws-kotlin-ssm = { module = "aws.sdk.kotlin:ssm", version.ref = "aws-kotlin" }
```

Add to `aws-java/build.gradle.kts` service dependencies:

```kotlin
compileOnly(libs.aws2.secretsmanager)
compileOnly(libs.aws2.ssm)
testImplementation(libs.aws2.secretsmanager)
testImplementation(libs.aws2.ssm)
```

Add to `aws-kotlin/build.gradle.kts` service dependencies:

```kotlin
compileOnly(libs.aws.kotlin.secretsmanager)
compileOnly(libs.aws.kotlin.ssm)
testImplementation(libs.aws.kotlin.secretsmanager)
testImplementation(libs.aws.kotlin.ssm)
```

- [ ] **Step 3: Implement Java `AwsSecretValue`**

Use a regular `Serializable` class patterned after `AwsRdsIamAuthToken`, with:

- private constructor
- `reveal()`
- redacted `toString()`
- constant-time equality with `MessageDigest.isEqual`
- redacted `hashCode()`
- companion `REDACTED`, `invoke`, and `of`
- top-level `awsSecretValueOf`
- English KDoc for the public class, factories, and `reveal()` warning that raw values must only cross explicit consumer boundaries

- [ ] **Step 4: Implement Kotlin module `AwsSecretValue`**

Use the same contract under package `io.bluetape4k.aws.kotlin.secretsmanager`.
Use the same KDoc and redaction guarantees as the Java module wrapper.

- [ ] **Step 5: Run redaction tests**

Run the same Gradle command from Step 1.

Expected: PASS.

## Task 2: Java SDK v2 Secrets Manager Helpers

**Complexity:** high

**Applies:** `$bluetape4k-code-patterns`, `$ecc-kotlin-testing`

**Files:** Java Secrets Manager main/test files from the file map.

- [ ] **Step 1: Write failing request/client/extension tests**

Tests must cover:

- `secretsManagerClientOf` and `secretsManagerAsyncClientOf` construct clients with local endpoint, region, and static dummy credentials.
- Java sync and async client factories follow existing `ShutdownQueue` ownership. If direct observation is unavailable, use the closest existing S3/SNS/STS factory test pattern and record the observation gap in DoD.
- request builders validate blank secret ids and reject more than 20 batch ids.
- `getSecretString` wraps `secretString` as `AwsSecretValue`.
- `getSecretString` fails safely when only `secretBinary` is present.
- `createSecret` and `putSecretValue` accept `AwsSecretValue` and do not expose sentinel values through helper `toString()`.
- async coroutine adapters call async methods and `await()`.
- coroutine adapters propagate `CancellationException` from suspended async calls without wrapping it.
- Java sync, async, and coroutine helpers propagate SDK missing-resource exceptions such as `ResourceNotFoundException` without normalizing them to empty success or wrapping the original AWS exception type, cause, request metadata, or message in a generic exception.
- coroutine cancellation tests include real `runTest` cancellation and a completed-exceptionally `CompletableFuture(CancellationException)` case.
- list/batch helpers make one SDK call per helper invocation, preserve `nextToken`/`maxResults`, and do not split batches, call `CompletableFuture.allOf`, or launch unbounded `async` fan-out.
- batch helper preserves raw SDK response errors rather than returning only successes.

Run:

```bash
./gradlew :bluetape4k-aws-java:test --tests '*SecretsManager*' --no-configuration-cache
```

Expected: FAIL because helpers do not exist.

- [ ] **Step 2: Implement client factories**

Follow `SnsClientSupport.kt` and `SnsAsyncClientSupport.kt`:

- `secretsManagerClient { }`
- `secretsManagerClientOf(region, httpClient, builder)`
- `secretsManagerClientOf(endpoint, region, credentialsProvider, httpClient, builder)`
- async equivalents
- register Java clients with `ShutdownQueue`

- [ ] **Step 3: Implement request builders**

Create focused builders:

- `getSecretValueRequestOf(secretId, versionId?, versionStage?, overrideConfiguration?, builder)`
- `batchGetSecretValueRequestOf(secretIds, maxResults?, nextToken?, overrideConfiguration?, builder)`
- `listSecretsRequestOf(maxResults?, nextToken?, overrideConfiguration?, builder)`
- `describeSecretRequestOf(secretId, overrideConfiguration?, builder)`
- `createSecretRequestOf(name, secretValue, description?, clientRequestToken?, overrideConfiguration?, builder)`
- `putSecretValueRequestOf(secretId, secretValue, clientRequestToken?, versionStages?, overrideConfiguration?, builder)`

- [ ] **Step 4: Implement sync/async/coroutine extensions**

Add common get/list/put helpers. Do not add delete wrappers. Batch helpers return raw SDK responses for partial failure preservation.
Add English KDoc to public factories, request builders, and extension helpers. Mutation helpers must state AWS-side mutation/version semantics and must not log or print secret values.
Do not add broad catch/wrap blocks except for redaction-specific safe failures such as string helpers receiving only binary payloads.

- [ ] **Step 5: Run Java Secrets Manager tests**

Run the command from Step 1.

Expected: PASS.

## Task 3: Java SDK v2 SSM Helpers

**Complexity:** high

**Applies:** `$bluetape4k-code-patterns`, `$ecc-kotlin-testing`

**Files:** Java SSM main/test files from the file map.

- [ ] **Step 1: Write failing SSM tests**

Tests must cover:

- client factory construction with local endpoint/static credentials
- Java sync and async client factories follow existing `ShutdownQueue` ownership. If direct observation is unavailable, use the closest existing S3/SNS/STS factory test pattern and record the observation gap in DoD.
- request validation for blank names/paths/tokens
- `getSecureParameter` maps `withDecryption = true` and returns `AwsSecretValue`
- non-secure `getParameter` maps `withDecryption = false`
- `putSecureParameter` accepts `AwsSecretValue` for `SecureString`; raw `String` write helpers are limited to explicitly non-secret `String` / `StringList` parameter APIs.
- no raw-string `SecureString` convenience overload exists, and secure write helper `toString()` / validation errors do not contain a sentinel secret.
- `getParameters` rejects more than 10 names
- `getParametersByPath` exposes `nextToken` and `maxResults` without hidden loops
- partial invalid parameters are preserved in raw SDK responses
- async coroutine adapters await async calls
- coroutine adapters propagate `CancellationException` from suspended async calls without wrapping it
- Java sync, async, and coroutine helpers propagate SDK missing-resource exceptions such as `ParameterNotFoundException` without normalizing them to empty success or wrapping the original AWS exception type, cause, request metadata, or message in a generic exception.
- coroutine cancellation tests include real `runTest` cancellation and a completed-exceptionally `CompletableFuture(CancellationException)` case.
- path/describe helpers make one SDK call per helper invocation, preserve `nextToken`/`maxResults`, and do not split batches, call `CompletableFuture.allOf`, or launch unbounded `async` fan-out.

Run:

```bash
./gradlew :bluetape4k-aws-java:test --tests '*Ssm*' --no-configuration-cache
```

Expected: FAIL before implementation.

- [ ] **Step 2: Implement SSM factories and request builders**

Mirror Secrets Manager style:

- `ssmClient { }`, `ssmClientOf(...)`
- `ssmAsyncClient { }`, `ssmAsyncClientOf(...)`
- request builders for get parameter, get parameters, get parameters by path, put secure parameter, put string parameter, put string-list parameter, and describe parameters

- [ ] **Step 3: Implement sync/async/coroutine extensions**

Add common get/list/put helpers. Do not add delete wrappers or hidden all-pages collection helpers.
Add English KDoc to public factories, request builders, and extension helpers. `putSecureParameter` KDoc must state SecureString plaintext handling, `overwrite` semantics, and caller responsibility. Non-secret write helpers must be named separately and must not accept raw strings for SecureString writes.

- [ ] **Step 4: Run Java SSM tests**

Run command from Step 1.

Expected: PASS.

## Task 4: AWS Kotlin SDK Secrets Manager And SSM Helpers

**Complexity:** high

**Applies:** `$bluetape4k-code-patterns`, `$ecc-kotlin-testing`, `$kotlin-coroutines-skill`

**Files:** Kotlin Secrets Manager and SSM files from the file map.

- [ ] **Step 1: Write failing Kotlin SDK tests**

Tests must cover:

- `secretsManagerClientOf`, `ssmClientOf`, `withSecretsManagerClient`, `withSsmClient`
- client factory tests use dummy static credentials, localhost endpoint, and explicit region; tests must structurally avoid the default credential provider chain and production AWS endpoints.
- `withXxxClient` closes on normal return, thrown exception, and cancellation
- request builder validation and batch limits
- exact spec operations: `getSecretString`, `listSecrets`, `describeSecret`, `createSecret`, `putSecretValue`, `batchGetSecretValues`, `getParameter`, `getSecureParameter`, `getParameters`, `getParametersByPath`, `describeParameters`, and `putParameter`
- SSM write APIs separate secure and non-secure writes: `putSecureParameter(..., AwsSecretValue, ...)` for `SecureString`, non-secret raw-string helpers only for `String` / `StringList`, and no raw-string `SecureString` overload.
- secure write helper `toString()` / validation errors do not contain a sentinel secret.
- no raw sentinel value appears in `toString()` or exception messages
- missing SDK exceptions propagate
- every suspend helper that catches broad exceptions, if any, rethrows `CancellationException` before wrapping or logging
- cancellation tests include real `runTest` / `Job.cancel()` coverage for `withSecretsManagerClient` and `withSsmClient`.
- list/path/describe helpers make one SDK call per helper invocation, preserve `nextToken`/`maxResults`, and do not split batches or launch unbounded `async` fan-out.
- collection helpers preserve partial errors/invalid parameters through raw SDK responses and do not silently return only successes

Run:

```bash
./gradlew :bluetape4k-aws-kotlin:test --tests '*SecretsManager*' --tests '*Ssm*' --no-configuration-cache
```

Expected: FAIL before implementation.

- [ ] **Step 2: Implement Kotlin client factories**

Mirror existing `sqsClientOf` / `withSqsClient` patterns:

- `secretsManagerClientOf(endpointUrl, region, credentialsProvider, httpClient, builder)`
- `withSecretsManagerClient(...)`
- `ssmClientOf(...)`
- `withSsmClient(...)`

- [ ] **Step 3: Implement Kotlin request builders and extensions**

Implement exact operations from the spec. Re-throw `CancellationException` before broad catch blocks when any catch block is introduced.
Add English KDoc to public factories, request builders, and suspend helpers. `xxxClientOf` helpers are caller-owned; `withXxxClient` helpers close clients via `useSafe` on normal return, thrown exception, and cancellation.

- [ ] **Step 4: Run Kotlin SDK tests**

Run command from Step 1.

Expected: PASS.

## Task 5: Documentation And Diagram Assets

**Complexity:** medium

**Applies:** `$bluetape4k-code-patterns`, `$bluetape4k-diagram`

**Files:** README locale sets and service coverage chart.

- [ ] **Step 1: Update README locale sets**

Update all required README files with:

- runtime dependencies
- compileOnly explanation
- direct examples for get secret string, get parameter, and get parameters by path
- unsupported capabilities
- mutation warnings
- hot-path caller-owned caching guidance
- no example that logs or prints revealed secret values
- local image/link references that still resolve after the chart update

Place a short capability boundary in the root README pair. Add a `Not provided by this module` section to both module README locale pairs covering Spring Environment loading, JSON flattening, caching, refresh, rotation orchestration, IAM/KMS policy management, and full all-pages pagination abstraction.

Run and record a README parity audit across `README.md`/`README.ko.md`, `aws-java/README.md`/`aws-java/README.ko.md`, and `aws-kotlin/README.md`/`aws-kotlin/README.ko.md`:

- required headings/sections present in both locales
- required runtime dependency snippets present in both locales
- required examples present in both locales
- unsupported capability and mutation warnings present in both locales
- code-block counts and service-name keyword counts reviewed for obvious drift

README examples must either be copied from compiling test fixtures or be manually source-checked against the implemented API names. If a snippet is not compiled, record `manual source-checked, not compiled` in PR DoD.

- [ ] **Step 2: Update service coverage chart SVG**

Mark `bluetape4k-aws-java` and `bluetape4k-aws-kotlin` coverage for Secrets Manager and Parameter Store as stable/supported according to the existing chart legend.

- [ ] **Step 3: Regenerate PNG and visually inspect**

Run:

```bash
svg=docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.svg
png=docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.png
xmllint --noout "$svg"
~/.local/bin/cairosvg "$svg" -o "$png" -s 2
file "$png"
grep -RInE 'println\\([^)]*reveal\\(|logger\\.[a-z]+\\([^)]*reveal\\(|log\\.[a-z]+\\([^)]*reveal\\(' README.md README.ko.md aws-java/README.md aws-java/README.ko.md aws-kotlin/README.md aws-kotlin/README.ko.md && exit 1 || true
python3 - <<'PY'
import re
from pathlib import Path
readmes = ["README.md", "README.ko.md", "aws-java/README.md", "aws-java/README.ko.md", "aws-kotlin/README.md", "aws-kotlin/README.ko.md"]
inline_link = re.compile(r'!?\[[^\]]*\]\(([^)\\s]+)(?:\\s+"[^"]*")?\)')
reference_def = re.compile(r'^\s*\[[^\]]+\]:\s+(\S+)', re.MULTILINE)
for readme in readmes:
    text = Path(readme).read_text()
    links = [m.group(1) for m in inline_link.finditer(text)]
    links.extend(m.group(1) for m in reference_def.finditer(text))
    missing = 0
    checked = 0
    for link in links:
        if link.startswith(("http://", "https://", "#", "mailto:")):
            continue
        target = (Path(readme).parent / link.split("#", 1)[0]).resolve()
        checked += 1
        if not target.exists():
            missing += 1
            print(f"{readme}: missing {link}")
    print(f"{readme}: local_links_checked={checked} missing={missing}")
    assert missing == 0
PY
```

Expected: SVG valid, PNG generated by CairoSVG at the expected dimensions, local README image/link references resolve, and no README example logs or prints revealed secret values. Record visual QA evidence ledger rows for SVG parse, CairoSVG render, PNG dimensions, full-size PNG inspection, no clipped text, no overlapping labels, English labels, correct Secrets Manager / Parameter Store cells, and `connector-heavy audits=N/A` because this service coverage chart is not a connector/card-flow diagram.

## Task 6: Verification, Review, Lessons, PR

**Complexity:** high

**Applies:** `$verification-before-completion`, `$bluetape4k-code-patterns`

- [ ] **Step 1: Run targeted compile/tests**

Run:

```bash
./gradlew :bluetape4k-aws-java:compileKotlin :bluetape4k-aws-java:compileTestKotlin :bluetape4k-aws-kotlin:compileKotlin :bluetape4k-aws-kotlin:compileTestKotlin :bluetape4k-aws-java:test --tests '*SecretsManager*' --tests '*Ssm*' :bluetape4k-aws-kotlin:test --tests '*SecretsManager*' --tests '*Ssm*' --no-configuration-cache
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run static/doc checks**

Run:

```bash
git diff --check
grep -RInE 'CompletableFuture\\.allOf|\\basync\\s*\\{|\\bwithTimeout(OrNull)?\\b|\\bdelay\\(|retry\\b|backoff\\b' aws-java/src/main/kotlin/io/bluetape4k/aws/secretsmanager aws-java/src/main/kotlin/io/bluetape4k/aws/ssm aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/secretsmanager aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/ssm || true
```

Expected: `git diff --check` has no output. The static grep has no custom retry/backoff/deadline/fan-out in touched helpers; any intentional match must be explained and tied to SDK/request override configuration rather than manual retry logic.

- [ ] **Step 3: Run API documentation and warning checks**

Review touched public APIs for English KDoc and run compile warnings:

```bash
./gradlew :bluetape4k-aws-java:compileTestKotlin :bluetape4k-aws-kotlin:compileTestKotlin --warning-mode all --no-configuration-cache
```

Expected: no unresolved deprecations or public API documentation gaps in touched code.

- [ ] **Step 4: Run Step 5 verifier against spec and plan**

Confirm every acceptance criterion maps to implementation and tests.

- [ ] **Step 5: Run Step 6-R code review**

Run module-sliced review for `aws-java`, `aws-kotlin`, and docs/chart changes. P0/P1 must be zero.

- [ ] **Step 6: Add lessons, commit, push, create PR**

Create `docs/lessons/2026-06-30-issue-268-secrets-parameter-core.md`, commit with Lore trailers, push, create PR closing #268, assign `debop`, copy issue milestone and labels, verify live PR body final section is `## DoD Status`.

PR DoD rows must include:

- README EN/KO parity audit for root, `aws-java`, and `aws-kotlin`
- runtime dependency snippets and `compileOnly` explanation
- examples verified and no revealed secret logging
- unsupported capabilities and mutation warnings
- SVG parse, CairoSVG render, PNG dimension, full-size visual inspection, and chart evidence ledger
- local image/link validation
- targeted compile/tests and static retry/fan-out grep

- [ ] **Step 7: CI and merge gate**

After PR checks pass, verify reviews/comments, then report DoD and wait for the user's merge instruction.
