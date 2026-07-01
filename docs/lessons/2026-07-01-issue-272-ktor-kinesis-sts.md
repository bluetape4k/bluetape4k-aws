# Issue 272 Ktor Kinesis And STS

## Context

Issue #272 adds Ktor-facing Kinesis and STS helpers after the lower-level
`aws-java` wrappers and Spring Kinesis patterns were already available.

## Decision

Keep the Ktor layer thin and Spring-free. Kinesis gets local request models,
plugin lifecycle, and an explicit single-shard cold `Flow`; STS gets identity
and temporary-session request helpers. Both layers return raw AWS SDK responses.

## Outcome

`aws-ktor` now exposes `KinesisKtorPlugin` and `StsKtorPlugin`, shared
`AwsKtorCore` customizers for both SDK clients, optional SDK dependency wiring,
and README locale coverage. The service coverage chart marks Ktor Kinesis and
STS support as optional SDK-dependent support.

## Verification

- Kinesis and STS targeted tests passed.
- Forced `aws-ktor` test compilation passed.
- Service coverage chart PNG was regenerated and visually inspected.

## Future Guidance

Do not turn `recordFlow` into a hidden listener container. Add lease
coordination, checkpointing, or KCL-style consumers only in a separate issue.
Treat STS as low-level identity/session operations until a dedicated Ktor auth
integration is designed.
