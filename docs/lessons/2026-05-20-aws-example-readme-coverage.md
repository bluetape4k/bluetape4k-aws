# AWS Example README Coverage

## Context

The AWS example modules `aws-ktor-dynamodb-examples`,
`aws-ktor-sqs-examples`, and `aws-spring-boot-dynamodb-examples` were missing
module READMEs while sibling examples already used multilingual README pairs
with PNG architecture diagrams.

## Decision

Add source-verified `README.md` and `README.ko.md` files for each missing
example module, and place matching SVG plus rendered PNG architecture diagrams
under `docs/images/readme-diagrams/`.

## Outcome

The new READMEs describe only endpoints, configuration, and API names found in
the current source. Diagram labels remain English-only and use the existing
README diagram font/style family.

## Verification

- Inspected target build files, Ktor route modules, Spring Boot controller,
  repository, application entrypoint, tests, resources, and sibling READMEs.
- Verified README image links resolve and referenced API tokens exist in source.
- Rendered all new SVG diagrams to PNG with `rsvg-convert`.
- Verified PNG dimensions with `identify`.

## Future Guard

For module-missing-readme fixes, inspect sibling README structure first, then
grep current source and tests for every endpoint, property, and public type
mentioned before writing docs.
