package io.bluetape4k.aws.spring.secretsmanager

/**
 * Secrets Manager 보안 문자열을 Spring 속성으로 노출하는 형식입니다.
 *
 * ## 계약
 *
 * `JSON`은 JSON 객체를 점으로 구분한 속성 키로 평탄화합니다. `TEXT`는 구성된 소스의
 * `prefix` 또는 `name`에 전체 보안 문자열을 노출합니다.
 */
enum class SecretFormat {
    JSON,
    TEXT,
}
