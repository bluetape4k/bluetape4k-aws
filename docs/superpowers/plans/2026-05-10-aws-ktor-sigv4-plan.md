# AWS Ktor SigV4 Client Plugin Plan

## Scope

Implement issue #8 only in `bluetape4k-aws/aws-ktor`.

## Tasks

1. Add `aws-ktor` source/resource skeleton and explicit AWS auth/Ktor test dependencies.
2. Implement `AwsSigV4PluginConfig` and `AwsSigV4AuthLocation`.
3. Implement Ktor-to-AWS request conversion helpers.
4. Implement `AwsSigV4Plugin` using Ktor `createClientPlugin` and `on(Send)`.
5. Add focused unit tests for signing output, config validation, session token, query auth, unsupported payload behavior, and HttpClient integration.
6. Add `aws-ktor/README.md` and `aws-ktor/README.ko.md` with language switch, dependency usage, plugin configuration, and payload limitations.
7. Run `:aws-ktor:test`, `:aws-ktor:compileKotlin`, and relevant docs/source review.
8. Commit with Lore protocol, push branch, and open a draft PR closing #8.

## Review Notes

- Adopt `AwsV4HttpSigner`; reject `Aws4Signer` because AWS SDK v2.44.4 marks it deprecated in local source.
- Use Ktor `Send` instead of `onRequest` because `Send` sees transformed `OutgoingContent`.
- Keep the first implementation header/query signing focused; streaming payload support can be added later by wrapping replayable content explicitly.
