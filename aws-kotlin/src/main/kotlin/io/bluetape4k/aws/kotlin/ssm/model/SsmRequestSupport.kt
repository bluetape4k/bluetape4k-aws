package io.bluetape4k.aws.kotlin.ssm.model

import aws.sdk.kotlin.services.ssm.model.DescribeParametersRequest
import aws.sdk.kotlin.services.ssm.model.GetParameterRequest
import aws.sdk.kotlin.services.ssm.model.GetParametersByPathRequest
import aws.sdk.kotlin.services.ssm.model.GetParametersRequest
import aws.sdk.kotlin.services.ssm.model.ParameterType
import aws.sdk.kotlin.services.ssm.model.PutParameterRequest
import io.bluetape4k.aws.kotlin.secretsmanager.AwsSecretValue
import io.bluetape4k.support.requireNotBlank

/**
 * Builds a [GetParameterRequest].
 */
inline fun getParameterRequestOf(
    name: String,
    withDecryption: Boolean = false,
    crossinline builder: GetParameterRequest.Builder.() -> Unit = {},
): GetParameterRequest {
    name.requireNotBlank("name")

    return GetParameterRequest {
        this.name = name
        this.withDecryption = withDecryption
        builder()
    }
}

/**
 * Builds a [GetParametersRequest] for at most ten names.
 */
inline fun getParametersRequestOf(
    names: Collection<String>,
    withDecryption: Boolean = false,
    crossinline builder: GetParametersRequest.Builder.() -> Unit = {},
): GetParametersRequest {
    require(names.isNotEmpty()) { "names must not be empty" }
    require(names.size <= 10) { "names size must be less than or equal to 10" }
    names.forEach { it.requireNotBlank("name") }

    return GetParametersRequest {
        this.names = names.toList()
        this.withDecryption = withDecryption
        builder()
    }
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
    crossinline builder: GetParametersByPathRequest.Builder.() -> Unit = {},
): GetParametersByPathRequest {
    path.requireNotBlank("path")
    nextToken?.requireNotBlank("nextToken")

    return GetParametersByPathRequest {
        this.path = path
        this.recursive = recursive
        this.withDecryption = withDecryption
        this.maxResults = maxResults
        this.nextToken = nextToken
        builder()
    }
}

/**
 * Builds a single-page [DescribeParametersRequest].
 */
inline fun describeParametersRequestOf(
    maxResults: Int? = null,
    nextToken: String? = null,
    crossinline builder: DescribeParametersRequest.Builder.() -> Unit = {},
): DescribeParametersRequest {
    nextToken?.requireNotBlank("nextToken")

    return DescribeParametersRequest {
        this.maxResults = maxResults
        this.nextToken = nextToken
        builder()
    }
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
    crossinline builder: PutParameterRequest.Builder.() -> Unit = {},
): PutParameterRequest =
    putParameterRequestOf(name, value.reveal(), ParameterType.SecureString, overwrite, description, builder)

/**
 * Builds a non-secret String [PutParameterRequest].
 */
inline fun putStringParameterRequestOf(
    name: String,
    value: String,
    overwrite: Boolean = false,
    description: String? = null,
    crossinline builder: PutParameterRequest.Builder.() -> Unit = {},
): PutParameterRequest =
    putParameterRequestOf(name, value, ParameterType.String, overwrite, description, builder)

/**
 * Builds a non-secret StringList [PutParameterRequest].
 */
inline fun putStringListParameterRequestOf(
    name: String,
    values: Collection<String>,
    overwrite: Boolean = false,
    description: String? = null,
    crossinline builder: PutParameterRequest.Builder.() -> Unit = {},
): PutParameterRequest {
    require(values.isNotEmpty()) { "values must not be empty" }
    values.forEach { it.requireNotBlank("value") }
    return putParameterRequestOf(name, values.joinToString(","), ParameterType.StringList, overwrite, description, builder)
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
    crossinline builder: PutParameterRequest.Builder.() -> Unit = {},
): PutParameterRequest {
    name.requireNotBlank("name")
    value.requireNotBlank("value")
    description?.requireNotBlank("description")

    return PutParameterRequest {
        this.name = name
        this.value = value
        this.type = type
        this.overwrite = overwrite
        this.description = description
        builder()
    }
}
