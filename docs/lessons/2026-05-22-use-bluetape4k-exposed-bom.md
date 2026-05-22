# Use bluetape4k-exposed BOM for Exposed Helper Artifacts

## Context

`bluetape4k-aws` consumed `bluetape4k-exposed-jdbc` with a direct version while
also importing JetBrains `exposed-bom`. That meant bluetape4k Exposed helper
artifacts were not governed by `bluetape4k-exposed-bom`.

## Decision

Add `io.github.bluetape4k.exposed:bluetape4k-exposed-bom` to the version
catalog. Remove the direct version from `bluetape4k-exposed-jdbc`, and add the
bluetape4k Exposed platform beside every module that directly depends on
`bluetape4k-exposed-jdbc`.

Do not import `bluetape4k-exposed-bom` globally at the root unless non-Exposed
modules also need its constraints. Keep its scope local to modules that consume
`io.github.bluetape4k.exposed:*` artifacts.

No `bluetape4k-exposed-jdbc-tests` dependency existed in this repository at the
time of the change. If one is added later, it must use the same BOM.

## Verification

- `./gradlew :bluetape4k-aws-exposed:compileKotlin :aws-spring-boot-exposed-examples:compileKotlin :aws-ktor-exposed-examples:compileKotlin --no-daemon --max-workers=1`
- `./gradlew :bluetape4k-aws-exposed:dependencies --configuration compileClasspath --no-daemon --max-workers=1`

The dependency report shows both `org.jetbrains.exposed:exposed-bom:1.3.0` and
`io.github.bluetape4k.exposed:bluetape4k-exposed-bom:1.8.1-SNAPSHOT`, with
`bluetape4k-exposed-jdbc` resolved to `1.8.1-SNAPSHOT` through the BOM.

## Future Guard

When adding any `io.github.bluetape4k.exposed:*` dependency in `bluetape4k-aws`,
first add or verify `platform(libs.bluetape4k.exposed.bom)` in that module.
