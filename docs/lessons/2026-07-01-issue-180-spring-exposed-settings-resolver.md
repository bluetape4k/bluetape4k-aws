# Issue #180 Spring Exposed Settings Resolver Lesson

## Context

#180 asked for Spring Boot integration that resolves `aws-exposed` database settings through Secrets Manager and Parameter Store.

## Decision

Reuse the existing Spring Environment post-processors instead of creating another AWS client path inside Exposed auto-configuration. The Exposed resolver reads the configured `secret-source` / `parameter-source` prefix and overlays only keys that actually exist in the Environment.

## Outcome

This keeps AWS loading, refresh, and fail-fast behavior in the existing Environment source layer while allowing `AwsExposedAutoConfiguration` to create a registry even when `default-database.url` is supplied only by a remote source.

## Future Guard

When adding framework adapters for remote settings, first check whether the framework already has a configuration-source lifecycle. Prefer a resolver that consumes that lifecycle over direct service-client calls from the adapter.
