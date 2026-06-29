package io.bluetape4k.aws.ssm.model

import io.bluetape4k.aws.secretsmanager.AwsSecretValue
import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.services.ssm.model.DescribeParametersRequest
import software.amazon.awssdk.services.ssm.model.GetParameterRequest
import software.amazon.awssdk.services.ssm.model.GetParametersByPathRequest
import software.amazon.awssdk.services.ssm.model.GetParametersRequest
import software.amazon.awssdk.services.ssm.model.ParameterType
import software.amazon.awssdk.services.ssm.model.PutParameterRequest

/**
 * Builds a [GetParameterRequest].
 */
inline fun getParameterRequestOf(
    name: String,
    withDecryption: Boolean = false,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: GetParameterRequest.Builder.() -> Unit = {},
): GetParameterRequest {
    name.requireNotBlank("name")

    return GetParameterRequest.builder()
        .name(name)
        .withDecryption(withDecryption)
        .apply {
            overrideConfiguration?.let { overrideConfiguration(it) }
            builder()
        }
        .build()
}

/**
 * Builds a [GetParametersRequest] for at most ten names.
 */
inline fun getParametersRequestOf(
    names: Collection<String>,
    withDecryption: Boolean = false,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: GetParametersRequest.Builder.() -> Unit = {},
): GetParametersRequest {
    require(names.isNotEmpty()) { "names must not be empty" }
    require(names.size <= 10) { "names size must be less than or equal to 10" }
    names.forEach { it.requireNotBlank("name") }

    return GetParametersRequest.builder()
        .names(names)
        .withDecryption(withDecryption)
        .apply {
            overrideConfiguration?.let { overrideConfiguration(it) }
            builder()
        }
        .build()
}

/**
 * Builds a single-page [GetParametersByPathRequest].
 */
inline fun getParametersByPathRequestOf(
    path: String,
    recursive: Boolean? = null,
    withDecryption: Boolean = false,
    maxResults: Int? = null,
    nextToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: GetParametersByPathRequest.Builder.() -> Unit = {},
): GetParametersByPathRequest {
    path.requireNotBlank("path")
    nextToken?.requireNotBlank("nextToken")

    return GetParametersByPathRequest.builder()
        .path(path)
        .withDecryption(withDecryption)
        .apply {
            recursive?.let { recursive(it) }
            maxResults?.let { maxResults(it) }
            nextToken?.let { nextToken(it) }
            overrideConfiguration?.let { overrideConfiguration(it) }
            builder()
        }
        .build()
}

/**
 * Builds a single-page [DescribeParametersRequest].
 */
inline fun describeParametersRequestOf(
    maxResults: Int? = null,
    nextToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: DescribeParametersRequest.Builder.() -> Unit = {},
): DescribeParametersRequest {
    nextToken?.requireNotBlank("nextToken")

    return DescribeParametersRequest.builder()
        .apply {
            maxResults?.let { maxResults(it) }
            nextToken?.let { nextToken(it) }
            overrideConfiguration?.let { overrideConfiguration(it) }
            builder()
        }
        .build()
}

/**
 * Builds a SecureString [PutParameterRequest] from a redacted value.
 *
 * The revealed value is placed into the AWS SDK request as plaintext for
 * transport to SSM. [overwrite] controls whether AWS may replace an existing
 * parameter value, and callers remain responsible for IAM/KMS policy and audit
 * boundaries.
 */
inline fun putSecureParameterRequestOf(
    name: String,
    value: AwsSecretValue,
    overwrite: Boolean = false,
    description: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: PutParameterRequest.Builder.() -> Unit = {},
): PutParameterRequest =
    putParameterRequestOf(name, value.reveal(), ParameterType.SECURE_STRING, overwrite, description, overrideConfiguration, builder)

/**
 * Builds a non-secret String [PutParameterRequest].
 */
inline fun putStringParameterRequestOf(
    name: String,
    value: String,
    overwrite: Boolean = false,
    description: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: PutParameterRequest.Builder.() -> Unit = {},
): PutParameterRequest =
    putParameterRequestOf(name, value, ParameterType.STRING, overwrite, description, overrideConfiguration, builder)

/**
 * Builds a non-secret StringList [PutParameterRequest].
 */
inline fun putStringListParameterRequestOf(
    name: String,
    values: Collection<String>,
    overwrite: Boolean = false,
    description: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: PutParameterRequest.Builder.() -> Unit = {},
): PutParameterRequest {
    require(values.isNotEmpty()) { "values must not be empty" }
    values.forEach { it.requireNotBlank("value") }
    return putParameterRequestOf(name, values.joinToString(","), ParameterType.STRING_LIST, overwrite, description, overrideConfiguration, builder)
}

/**
 * Builds a typed [PutParameterRequest].
 *
 * Prefer [putSecureParameterRequestOf] for `SecureString` writes so secret
 * values stay wrapped until the SDK request boundary.
 */
inline fun putParameterRequestOf(
    name: String,
    value: String,
    type: ParameterType,
    overwrite: Boolean = false,
    description: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: PutParameterRequest.Builder.() -> Unit = {},
): PutParameterRequest {
    name.requireNotBlank("name")
    value.requireNotBlank("value")
    description?.requireNotBlank("description")

    return PutParameterRequest.builder()
        .name(name)
        .value(value)
        .type(type)
        .overwrite(overwrite)
        .apply {
            description?.let { description(it) }
            overrideConfiguration?.let { overrideConfiguration(it) }
            builder()
        }
        .build()
}
