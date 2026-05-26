## Context

Adopted the JetBrains Exposed Gradle plugin for AWS Exposed example modules after the shared dependency catalog added a central plugin alias.

## Decision

Library repositories should consume the plugin alias from the managed `bt4k` catalog and pin the default catalog ref to `catalog/2026-05-26-00`.

## Outcome

`aws-spring-boot-exposed-examples` and `aws-ktor-exposed-examples` now expose the `generateMigrations` task with explicit table package and H2 migration database settings.

## Verification

Ran `git diff --check`, `./gradlew -q help`, and `tasks --all` for both Exposed example modules.

## Future Guard

Keep workshop-style repositories independent from the managed catalog; only bluetape4k library repos should consume `bt4k.plugins.exposed.plugin`.
