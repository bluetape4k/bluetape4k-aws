---
title: Ktor client와 SigV4
description: Ktor client 요청을 올바르게 서명하고 canonical request 실패를 진단합니다.
manualId: bluetape4k-aws-ktor
chapterId: client-and-sigv4
---

# Ktor client와 SigV4

`AwsSigV4Plugin`은 Ktor client의 외부 요청에 AWS Signature Version 4 서명을 붙입니다. 최종 method, URL, header, body, region, service, credentials, clock이 모두 맞아야 올바른 서명이 됩니다.

## Plugin 설치

```kotlin
val client = HttpClient(CIO) {
    install(AwsSigV4Plugin) {
        region = "ap-northeast-2"
        service = "execute-api"
        credentialsProvider = credentials
        authLocation = AwsSigV4AuthLocation.Header
    }
}
```

요청을 보낼 때마다 credential provider를 조회하므로 교체되는 credentials를 사용할 수 있습니다. provider 자체는 요청 경로 밖에서 한 번 만드세요.

## Body를 다시 읽을 수 있어야 한다

payload signing을 켜면 plugin이 body를 hash한 뒤 전송할 수 있도록 다시 읽을 수 있어야 합니다. 재생할 수 없는 stream은 unsigned payload나 서비스별 전략을 명시적으로 선택해야 합니다. 크기 제한 없이 전부 buffering하면 안 됩니다.

## Canonical request 옵션

`doubleUrlEncode`, `normalizePath`, header·query 인증 선택은 canonical request를 바꿉니다. 기본값을 변경하기 전에 서비스 문서와 고정 test vector로 검증하세요. presigned URL은 제한된 만료 시간과 안정적인 signing clock도 필요합니다.

## 실패 진단

403 signature mismatch는 대부분 잘못된 region·service, 달라진 path encoding, clock skew, credentials, 서명 후 body·header 변경에서 생깁니다. request ID와 안전한 canonical metadata만 로그에 남기고 secret access key나 signature 재료는 기록하지 마세요.

## 결정적인 테스트

고정 credentials와 `Clock`을 주입하고 test signer 또는 AWS test vector와 비교하세요. 반복 header, query parameter, 빈 body, 비 ASCII path를 포함해야 합니다.

## 근거 자료

- [SigV4 client plugin](../../../../../aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/client/AwsSigV4Plugin.kt)
- [SigV4 설정](../../../../../aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/client/AwsSigV4PluginConfig.kt)
- [SigV4 테스트](../../../../../aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/client/AwsSigV4PluginTest.kt)
