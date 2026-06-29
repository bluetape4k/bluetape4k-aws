package io.bluetape4k.aws.kotlin.ssm

import aws.sdk.kotlin.services.ssm.SsmClient
import aws.sdk.kotlin.services.ssm.describeParameters
import aws.sdk.kotlin.services.ssm.getParameter
import aws.sdk.kotlin.services.ssm.getParameters
import aws.sdk.kotlin.services.ssm.getParametersByPath
import aws.sdk.kotlin.services.ssm.model.DescribeParametersResponse
import aws.sdk.kotlin.services.ssm.model.GetParameterResponse
import aws.sdk.kotlin.services.ssm.model.GetParametersByPathResponse
import aws.sdk.kotlin.services.ssm.model.GetParametersResponse
import aws.sdk.kotlin.services.ssm.model.PutParameterResponse
import aws.sdk.kotlin.services.ssm.putParameter
import io.bluetape4k.aws.kotlin.secretsmanager.AwsSecretValue
import io.bluetape4k.aws.kotlin.secretsmanager.awsSecretValueOf
import io.bluetape4k.aws.kotlin.ssm.model.describeParametersRequestOf
import io.bluetape4k.aws.kotlin.ssm.model.getParameterRequestOf
import io.bluetape4k.aws.kotlin.ssm.model.getParametersByPathRequestOf
import io.bluetape4k.aws.kotlin.ssm.model.getParametersRequestOf
import io.bluetape4k.aws.kotlin.ssm.model.putSecureParameterRequestOf
import io.bluetape4k.aws.kotlin.ssm.model.putStringListParameterRequestOf
import io.bluetape4k.aws.kotlin.ssm.model.putStringParameterRequestOf

/**
 * Gets a parameter without decryption by default.
 */
suspend fun SsmClient.getParameter(
    name: String,
    withDecryption: Boolean = false,
): GetParameterResponse =
    getParameter(getParameterRequestOf(name, withDecryption))

/**
 * Gets a SecureString parameter with decryption enabled and redacts the value.
 */
suspend fun SsmClient.getSecureParameter(name: String): AwsSecretValue =
    awsSecretValueOf(getParameter(name, withDecryption = true).parameter?.value ?: error("Parameter value is not present."))

/**
 * Gets up to ten parameters and preserves raw SDK invalid-parameter details.
 */
suspend fun SsmClient.getParameters(
    names: Collection<String>,
    withDecryption: Boolean = false,
): GetParametersResponse =
    getParameters(getParametersRequestOf(names, withDecryption))

/**
 * Gets one parameter page by path.
 */
suspend fun SsmClient.getParametersByPath(
    path: String,
    recursive: Boolean? = null,
    withDecryption: Boolean = false,
    maxResults: Int? = null,
    nextToken: String? = null,
): GetParametersByPathResponse =
    getParametersByPath(getParametersByPathRequestOf(path, recursive, withDecryption, maxResults, nextToken))

/**
 * Describes one page of parameters.
 */
suspend fun SsmClient.describeParameters(
    maxResults: Int? = null,
    nextToken: String? = null,
): DescribeParametersResponse =
    describeParameters(describeParametersRequestOf(maxResults, nextToken))

/**
 * Puts a SecureString parameter from a redacted value.
 *
 * This mutates AWS-side state and sends the revealed value to SSM as
 * SecureString plaintext. [overwrite] controls whether an existing value may be
 * replaced. Callers remain responsible for IAM/KMS policy and audit boundaries.
 * Do not log or print the revealed value.
 */
suspend fun SsmClient.putSecureParameter(
    name: String,
    value: AwsSecretValue,
    overwrite: Boolean = false,
    description: String? = null,
): PutParameterResponse =
    putParameter(putSecureParameterRequestOf(name, value, overwrite, description))

/**
 * Puts a non-secret String parameter.
 */
suspend fun SsmClient.putStringParameter(
    name: String,
    value: String,
    overwrite: Boolean = false,
    description: String? = null,
): PutParameterResponse =
    putParameter(putStringParameterRequestOf(name, value, overwrite, description))

/**
 * Puts a non-secret StringList parameter.
 */
suspend fun SsmClient.putStringListParameter(
    name: String,
    values: Collection<String>,
    overwrite: Boolean = false,
    description: String? = null,
): PutParameterResponse =
    putParameter(putStringListParameterRequestOf(name, values, overwrite, description))
