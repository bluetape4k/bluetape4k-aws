# Issue #197 Ktor AWS Core

## Context

`aws-ktor` needed the same operational idea as the Spring AWS core defaults
introduced in #190, but Ktor must use application/plugin configuration instead
of Spring beans.

## Decision

Use an opt-in `AwsKtorCore` plugin that stores shared defaults in application
attributes. Service plugins read those defaults during installation, and
service-local settings keep precedence. Model the shared defaults as a
bluetape4k `AbstractValueObject`, but keep live providers, engines, and
customizers transient because they are runtime collaborators.

## Outcome

- S3 gets a defaults-based factory overload.
- SQS can now create a plugin-owned client and closes it once.
- DynamoDB plugin-created clients inherit shared defaults and customizers.
- README now has a Graphviz-grounded `aws-ktor` architecture diagram.

## Verification Evidence

- Targeted compile and tests were run during implementation.
- The architecture PNG was rendered from SVG and visually inspected.

## Future Guard

For Ktor integrations, keep injected-client ownership separate from
plugin-created ownership. If a README diagram is touched, apply
`bluetape4k-diagram`: Graphviz evidence, SVG+PNG, README PNG embed, and rendered
PNG inspection are part of the DoD.
