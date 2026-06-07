# Issue #191 DynamoDB DAX Spring Boot Design

Date: 2026-06-07
Issue: https://github.com/bluetape4k/bluetape4k-aws/issues/191

## Context

`bluetape4k-aws-spring-boot` already auto-configures DynamoDB through
`DynamoDbAsyncClient`, `DynamoDbEnhancedAsyncClient`, and
`DynamoDbTableNameResolver`. Repository users depend on
`DynamoDbEnhancedAsyncClient`, so DAX support should plug into the client layer
without changing repository classes.

External reference points:

- Spring Cloud AWS 4.0.2 exposes DynamoDB DAX properties under its DynamoDB
  integration, including endpoint URL, timeout, retry, and concurrency knobs.
- AWS DynamoDB DAX Java 2.x documentation uses
  `software.amazon.dax:amazon-dax-client` and `ClusterDaxAsyncClient`, which
  implements the AWS SDK v2 `DynamoDbAsyncClient` contract.
- Maven metadata checked on 2026-06-07 KST reports
  `software.amazon.dax:amazon-dax-client` latest/release `2.0.9`.
- Local `javap` inspection of `amazon-dax-client-2.0.9.jar` shows
  `ClusterDaxAsyncClient.Builder` accepts only
  `overrideConfiguration(software.amazon.dax.Configuration)` and
  `Configuration.Builder` directly supports URL, region, credentials, timeout,
  retry, concurrency, hostname-verification, and metrics options.
- Local `ApplicationContextRunner` feedback showed `Configuration.Builder`
  resolves credentials during bean construction, so DAX-enabled tests and
  applications need a resolvable `AwsCredentialsProvider`.

## Problem

DAX is currently invisible to bluetape4k Spring Boot users:

- no `bluetape4k.aws.dynamodb.dax.*` properties
- no optional DAX client bean
- no automatic way for `DynamoDbEnhancedAsyncClient` to use DAX
- no user documentation explaining DAX caveats or why LocalStack/DynamoDB Local
  tests remain separate from DAX

## Goals

1. Keep default DynamoDB behavior unchanged when DAX is disabled or absent.
2. Add opt-in DAX client wiring guarded by the DAX SDK classpath.
3. Build `DynamoDbEnhancedAsyncClient` on the selected `DynamoDbAsyncClient`, so
   existing repository code uses DAX without API changes.
4. Cover classpath absence, enabled endpoint binding, and enhanced-client
   selection with `ApplicationContextRunner` tests.
5. Document configuration and operational caveats in `README.md` and
   `README.ko.md`.

## Non-Goals

- No real AWS DAX cluster in local or CI tests.
- No repository API that hides DAX consistency or cache behavior.
- No awspring template/Spring Integration adapter cloning.
- No broad DynamoDB repository refactor.

## Proposed User API

Properties stay under the existing bluetape4k namespace:

```yaml
bluetape4k:
  aws:
    dynamodb:
      region: us-east-1
      dax:
        enabled: true
        url: dax://orders-cache.abc123.dax-clusters.us-east-1.amazonaws.com
        connect-timeout: 1s
        request-timeout: 1s
        idle-timeout: 30s
        connection-ttl: 0s
        read-retries: 2
        write-retries: 2
        cluster-update-interval: 4s
        endpoint-refresh-timeout: 6s
        max-concurrency: 1000
        max-pending-connection-acquires: 10000
        skip-host-name-verification: false
```

`dax.url` is required only when `dax.enabled=true`. Region and credentials come
from existing DynamoDB/global AWS configuration.

## Design

### Properties

Extend `DynamoDbProperties` with a nested `DynamoDbDaxProperties` value.

Validation:

- `dax.url` must be present when DAX is enabled.
- retry and concurrency values must be non-negative.
- timeout durations must be non-negative.
- existing `endpointOverride` + `region` validation remains unchanged.
- DAX validation should run only when the DAX auto-configuration is active, so
  default users without the DAX SDK do not fail property binding.

### Auto-Configuration

Split DAX wiring into a separate auto-configuration class:

- `DynamoDbAutoConfiguration`
  - keeps default `DynamoDbAsyncClient` bean behavior
  - keeps `DynamoDbEnhancedAsyncClient` over the available
    `DynamoDbAsyncClient`
- `DynamoDbDaxAutoConfiguration`
  - guarded with `@ConditionalOnClass(name = ["software.amazon.dax.ClusterDaxAsyncClient"])`
  - guarded with
    `@ConditionalOnProperty(prefix = "bluetape4k.aws.dynamodb.dax", name = ["enabled"], havingValue = "true")`
  - defines `DynamoDbAsyncClient` with `ClusterDaxAsyncClient` when no custom
    `DynamoDbAsyncClient` bean exists
  - applies existing credentials resolution
  - does not apply `AwsAsyncClientCustomizer` or
    `AwsClientCustomizer<DynamoDbAsyncClientBuilder>` because the DAX builder is
    not an AWS SDK `AwsAsyncClientBuilder`; DAX-specific tuning is represented
    through typed properties instead

Ordering:

- register `DynamoDbDaxAutoConfiguration` before `DynamoDbAutoConfiguration`
  so the DAX `DynamoDbAsyncClient` can satisfy the enhanced-client bean.
- keep both classes independently guarded by
  `bluetape4k.aws.dynamodb.enabled`.

### Dependency Boundary

Add `software.amazon.dax:amazon-dax-client:2.0.9` as `compileOnly` and
`testImplementation` in `aws-spring-boot`.

Consumers opt in by adding the same dependency at runtime. Without it, the DAX
auto-configuration class must not load and default DynamoDB behavior must remain
unchanged.

`amazon-dax-client:2.0.9` declares a transitive dependency on
`software.amazon.awssdk:dynamodb:2.38.5`. The implementation must run
`dependencyInsight` after adding the dependency and verify the repo's AWS SDK
BOM/catalog line still selects the intended AWS SDK version.

### Repository Selection

No repository code changes are required. `AbstractCoroutinesDynamoDbRepository`
already depends on `DynamoDbEnhancedAsyncClient`, and the enhanced async client
is built with the currently selected `DynamoDbAsyncClient`.

### Tests

Use `ApplicationContextRunner` only; no live DAX cluster.

Required tests:

- default DynamoDB beans remain regular SDK clients when DAX is disabled
- DAX classpath absence backs off even when `dax.enabled=true`
- `dax.enabled=true` without `dax.url` fails with a clear validation message
- `dax.enabled=true` with URL registers a single `DynamoDbAsyncClient`
- enhanced client is still registered and uses the selected async client path
- custom `DynamoDbAsyncClient` still wins over default/DAX auto-configuration

Because `ClusterDaxAsyncClient.builder().build()` validates configuration and
resolves credentials during bean construction, tests must provide dummy
credentials, avoid network calls, and assert bean shape/selection only.

## Risks

- The DAX client API is not managed by the AWS SDK BOM and has a separate
  version line.
- DAX client builder APIs differ from normal AWS SDK builders, so global/service
  AWS SDK customizers cannot be applied directly.
- The DAX client POM pins an older AWS SDK DynamoDB dependency; Gradle dependency
  management must keep the repository AWS SDK line in control.
- DAX is a read-through/write-through cache with consistency tradeoffs; docs
  must avoid implying it is equivalent to DynamoDB Local or LocalStack.

## Acceptance Mapping

| Acceptance Criteria | Design Coverage |
|---|---|
| No DAX dependency required unless opt in | `compileOnly`, classpath guard, docs |
| Default behavior unchanged | missing/disabled DAX tests |
| Classpath absence covered | `FilteredClassLoader("software.amazon.dax")` test |
| Enabled endpoint covered | DAX URL property and context-runner test |
| Repository/client selection covered | enhanced client uses selected async client path |
| README examples/caveats | root and module locale README updates |

## DoD

- Spec review: `P0=0`, `P1=0`.
- Plan review: `P0=0`, `P1=0`.
- Targeted tests pass for `:bluetape4k-aws-spring-boot:test`.
- `git diff --check` passes.
- PR body ends with `## DoD Status`.
