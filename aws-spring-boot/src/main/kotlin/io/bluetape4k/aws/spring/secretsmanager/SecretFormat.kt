package io.bluetape4k.aws.spring.secretsmanager

/**
 * Format used to expose a Secrets Manager secret string as Spring properties.
 *
 * ## Contract
 *
 * `JSON` flattens a JSON object into dot-separated property keys. `TEXT`
 * exposes the whole secret string at the configured source `prefix` or `name`.
 */
enum class SecretFormat {
    JSON,
    TEXT,
}

