# Issue #197 Ktor AWS Core Plan

Date: 2026-05-26
Issue: https://github.com/bluetape4k/bluetape4k-aws/issues/197

## Tasks

1. Add shared Ktor AWS defaults.
   - Create `AwsKtorCore`, `AwsKtorDefaults`, and customizer interfaces.
   - Store defaults in Ktor application attributes.

2. Wire service integrations.
   - Add `s3KtorClientOf(defaults = ...)`.
   - Let `SqsConsumer` create and close plugin-owned clients from shared or
     service-local settings.
   - Let `DynamoDbKtorPlugin` inherit shared region, endpoint, credentials,
     HTTP engine, and customizers.

3. Add tests.
   - Core defaults storage.
   - S3 defaults inheritance.
   - SQS defaults inheritance and ownership.
   - DynamoDB defaults inheritance and customizer order.

4. Refresh README docs and diagram.
   - Update `README.md` and `README.ko.md`.
   - Generate `aws-ktor-architecture-01.dot`, `.plain`, sketch SVG, final SVG,
     and PNG.
   - Inspect rendered PNG before declaring diagram done.

5. Verify.
   - Compile `:bluetape4k-aws-ktor`.
   - Run targeted tests.
   - Run full `:bluetape4k-aws-ktor:test` if targeted tests pass.
   - Run `git diff --check`.
   - Run Claude advisor code review and require P0/P1 = 0.

## Stop Condition

PR is open for #197 with passing local verification evidence, updated README
diagram assets, and a lesson entry.
