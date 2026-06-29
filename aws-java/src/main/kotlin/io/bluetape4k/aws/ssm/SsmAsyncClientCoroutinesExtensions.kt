package io.bluetape4k.aws.ssm

import io.bluetape4k.aws.secretsmanager.AwsSecretValue
import kotlinx.coroutines.future.await
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.services.ssm.SsmAsyncClient
import software.amazon.awssdk.services.ssm.model.DescribeParametersResponse
import software.amazon.awssdk.services.ssm.model.GetParameterResponse
import software.amazon.awssdk.services.ssm.model.GetParametersByPathResponse
import software.amazon.awssdk.services.ssm.model.GetParametersResponse
import software.amazon.awssdk.services.ssm.model.PutParameterResponse

/**
 * Gets a parameter without decryption by default and awaits completion.
 */
suspend fun SsmAsyncClient.getParameter(
    name: String,
    withDecryption: Boolean = false,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): GetParameterResponse =
    getParameterAsync(name, withDecryption, overrideConfiguration).await()

/**
 * Gets a SecureString parameter with decryption enabled and awaits completion.
 */
suspend fun SsmAsyncClient.getSecureParameter(
    name: String,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): AwsSecretValue =
    getSecureParameterAsync(name, overrideConfiguration).await()

/**
 * Gets up to ten parameters and awaits completion.
 */
suspend fun SsmAsyncClient.getParameters(
    names: Collection<String>,
    withDecryption: Boolean = false,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): GetParametersResponse =
    getParametersAsync(names, withDecryption, overrideConfiguration).await()

/**
 * Gets one parameter page by path and awaits completion.
 */
suspend fun SsmAsyncClient.getParametersByPath(
    path: String,
    recursive: Boolean? = null,
    withDecryption: Boolean = false,
    maxResults: Int? = null,
    nextToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): GetParametersByPathResponse =
    getParametersByPathAsync(path, recursive, withDecryption, maxResults, nextToken, overrideConfiguration).await()

/**
 * Describes one page of parameters and awaits completion.
 */
suspend fun SsmAsyncClient.describeParameters(
    maxResults: Int? = null,
    nextToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): DescribeParametersResponse =
    describeParametersAsync(maxResults, nextToken, overrideConfiguration).await()

/**
 * Puts a SecureString parameter from a redacted value and awaits completion.
 *
 * This mutates AWS-side state and sends the revealed value to SSM as
 * SecureString plaintext. [overwrite] controls whether an existing value may be
 * replaced. Callers remain responsible for IAM/KMS policy and audit boundaries.
 * Do not log or print the revealed value.
 */
suspend fun SsmAsyncClient.putSecureParameter(
    name: String,
    value: AwsSecretValue,
    overwrite: Boolean = false,
    description: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): PutParameterResponse =
    putSecureParameterAsync(name, value, overwrite, description, overrideConfiguration).await()

/**
 * Puts a non-secret String parameter and awaits completion.
 */
suspend fun SsmAsyncClient.putStringParameter(
    name: String,
    value: String,
    overwrite: Boolean = false,
    description: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): PutParameterResponse =
    putStringParameterAsync(name, value, overwrite, description, overrideConfiguration).await()

/**
 * Puts a non-secret StringList parameter and awaits completion.
 */
suspend fun SsmAsyncClient.putStringListParameter(
    name: String,
    values: Collection<String>,
    overwrite: Boolean = false,
    description: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): PutParameterResponse =
    putStringListParameterAsync(name, values, overwrite, description, overrideConfiguration).await()
