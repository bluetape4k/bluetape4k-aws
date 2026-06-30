# Issue #271 Ktor SES And SNS

Issue #271 completed the Ktor-side SES v2 and SNS support after the core Java,
Kotlin, and Spring paths already covered the services.

## Decision

Add thin Ktor lifecycle plugins over AWS SDK v2 async clients instead of adding
another transport abstraction. `AwsKtorCore` now carries SES v2 and SNS async
client customizers, while `SesKtorPlugin` and `SnsKtorPlugin` follow the
existing ownership contract: injected clients and operations are application
owned, plugin-created clients are closed on `ApplicationStopping`.

SNS HTTP endpoint parsing remains intentionally untrusted. The parser validates
JSON shape, duplicate fields, message type headers, signing certificate URL
shape, partition, and region, but callers must still validate the signature,
certificate chain, expected topic ARN, and replay policy before wrapping with
`TrustedSnsHttpMessage`.

## Outcome

`aws-ktor` now exposes coroutine SES simple/template/raw email operations and
SNS topic creation, topic lookup, topic publish, SMS publish, subscription
confirmation by explicit token, and SNS HTTP endpoint message parsing. The
module README files and root service coverage chart were updated together.

## Verification

- `./gradlew :bluetape4k-aws-ktor:compileKotlin :bluetape4k-aws-ktor:compileTestKotlin --warning-mode all`
- `./gradlew :bluetape4k-aws-ktor:test --tests '*SnsKtorTemplateAwsEmulatorTest'`
- `./gradlew :bluetape4k-aws-ktor:test --tests '*Ses*' --tests '*Sns*'`
- `./gradlew :bluetape4k-aws-ktor:test`
- `./gradlew --no-configuration-cache :bluetape4k-aws-ktor:generateMetadataFileForBluetapeAwsPublication :bluetape4k-aws-ktor:generatePomFileForBluetapeAwsPublication`
- `./gradlew detekt`
- `./gradlew build -x test --parallel`
- `xmllint --noout docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.svg`
- `rsvg-convert docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.svg -o docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.png`
- `git diff --check`

The publication metadata tasks require `--no-configuration-cache` in this build
because the existing Maven POM `withXml` customization is not configuration-cache
compatible.

## Future Notes

Do not treat `TrustedSnsHttpMessage.fromVerified` as a signature verifier. It is
only a type marker for caller-verified messages. If this repo adds built-in SNS
signature verification later, keep the parser and verifier as separate steps so
tests can cover URL validation and cryptographic verification independently.
