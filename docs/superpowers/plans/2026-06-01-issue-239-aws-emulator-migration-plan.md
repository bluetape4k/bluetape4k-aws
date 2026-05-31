# Issue 239 AWS Emulator Migration Plan

Issue: [#239](https://github.com/bluetape4k/bluetape4k-aws/issues/239)
Date: 2026-06-01

## Decision

`bluetape4k-aws` keeps a **Floci-first** emulator migration policy.

- Floci is the preferred default for new or migrated emulator-aware tests.
- LocalStack remains an explicit fallback for legacy behavior and fidelity gaps.
- MiniStack is an evaluation backend only until the same SDK smoke matrix passes
  repeatedly for the target module.

MiniStack service-count claims are not enough to change the default. The
adoption signal must be the AWS SDK calls used by this repository.

## Current Matrix

| Scope | Current default | Supported override | Next action |
|---|---|---|---|
| `bluetape4k-aws-spring-boot` | Floci | `floci`, `localstack`, `ministack` through `AwsSpringBootTestEmulator` | Use as the first smoke matrix target |
| `aws-ktor-sqs-examples` | Floci | Direct Floci fixture | Keep Floci-first; factor only if reuse grows |
| `bluetape4k-aws-java` | Floci | `floci`, `localstack` through the shared AWS test base | LocalStack covers Floci API gaps |
| `bluetape4k-aws-kotlin` | Floci | `floci`, `localstack` through the shared AWS test base | LocalStack covers Floci API gaps |
| `bluetape4k-aws-ktor` | Floci | `floci`, `localstack` for emulator-aware tests | LocalStack covers Floci API gaps |
| Example modules | Floci where AWS-emulator-aware | `floci`, `localstack` for migrated examples | Continue avoiding churn in non-AWS-emulator examples |

## Smoke Matrix

Run Testcontainers-backed checks serially.

| Service | Required behavior | Floci | MiniStack | LocalStack |
|---|---|---|---|---|
| S3 | bucket/object CRUD, path-style endpoint, presigned URL where module uses it | Required | Compare | Fallback |
| SQS | queue create/send/receive/delete, visibility timeout | Required | Compare | Fallback |
| SNS | topic to SQS fanout | Required | Compare | Fallback |
| DynamoDB | table CRUD/query/index path where module uses it | Required | Compare | Fallback |
| KMS | encrypt/decrypt path used by Spring tests | Required | Compare | Fallback |
| Secrets Manager / SSM | environment post-processor paths | Required | Compare | Fallback |

## Verification Order

1. Keep documentation and agent guidance aligned with Floci-first policy.
2. Verify `bluetape4k-aws-spring-boot` with default Floci.
3. Run the same module with MiniStack as comparison evidence.
4. Keep LocalStack fallback available until gaps are documented and resolved.
5. Continue treating LocalStack as an explicit fallback for Floci API gaps.

## Verification Evidence

- `./gradlew :bluetape4k-aws-spring-boot:test --tests '*AwsEmulatorTest' -Dbluetape4k.aws.emulator=floci`
  passed: 34 tests.
- `./gradlew :bluetape4k-aws-spring-boot:test --tests '*AwsEmulatorTest' -Dbluetape4k.aws.emulator=ministack`
  failed: 33 tests passed, 1 failed. The failing SQS FIFO test received a
  `null` message group id instead of `orders`, so MiniStack is not ready to
  replace Floci as the default for this module.
- `./gradlew :bluetape4k-aws-spring-boot:test --tests '*AwsEmulatorTest' -Dbluetape4k.aws.emulator=localstack`
  passed: 34 tests, confirming the explicit fallback path still works.
