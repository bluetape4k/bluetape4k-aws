# Issue #271 Ktor SES 및 SNS

Issue #271에서는 core Java, Kotlin, Spring 경로가 이미 service를 지원한 뒤 Ktor 측 SES
v2와 SNS 지원을 완성했다.

## 결정

또 다른 transport abstraction을 추가하지 않고 AWS SDK v2 async client 위에 얇은 Ktor
lifecycle plugin을 추가한다. 이제 `AwsKtorCore`는 SES v2와 SNS async client customizer를
제공한다. `SesKtorPlugin`과 `SnsKtorPlugin`은 기존 ownership contract를 따른다. 주입한
client와 operation은 application이 소유하고, plugin이 만든 client는
`ApplicationStopping`에서 닫는다.

SNS HTTP endpoint parsing은 의도적으로 신뢰하지 않는다. Parser는 JSON shape, duplicate
field, message type header, signing certificate URL shape, partition, region을 검증한다.
하지만 `TrustedSnsHttpMessage`로 감싸기 전에 호출자가 signature, certificate chain, 예상
topic ARN, replay policy를 계속 검증해야 한다.

## 결과

이제 `aws-ktor`는 coroutine SES simple/template/raw email operation과 SNS topic 생성,
topic 조회, topic 게시, SMS 게시, 명시적 token을 통한 subscription 확인, SNS HTTP
endpoint message parsing을 제공한다. Module README와 root service coverage chart를 함께
갱신했다.

## 검증

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

기존 Maven POM `withXml` customization이 configuration cache와 호환되지 않으므로 이
build에서 publication metadata task에는 `--no-configuration-cache`가 필요하다.

## 향후 참고

`TrustedSnsHttpMessage.fromVerified`를 signature verifier로 취급하지 않는다. 호출자가
검증한 message를 나타내는 type marker일 뿐이다. 나중에 이 저장소가 SNS signature
verification을 내장하면 parser와 verifier를 별도 단계로 유지해 URL validation과
cryptographic verification을 독립적으로 테스트할 수 있게 한다.
