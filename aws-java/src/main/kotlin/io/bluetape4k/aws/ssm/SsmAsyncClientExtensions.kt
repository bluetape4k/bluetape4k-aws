package io.bluetape4k.aws.ssm

import io.bluetape4k.aws.secretsmanager.AwsSecretValue
import io.bluetape4k.aws.secretsmanager.awsSecretValueOf
import io.bluetape4k.aws.ssm.model.describeParametersRequestOf
import io.bluetape4k.aws.ssm.model.getParameterRequestOf
import io.bluetape4k.aws.ssm.model.getParametersByPathRequestOf
import io.bluetape4k.aws.ssm.model.getParametersRequestOf
import io.bluetape4k.aws.ssm.model.putSecureParameterRequestOf
import io.bluetape4k.aws.ssm.model.putStringListParameterRequestOf
import io.bluetape4k.aws.ssm.model.putStringParameterRequestOf
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.services.ssm.SsmAsyncClient
import software.amazon.awssdk.services.ssm.model.DescribeParametersResponse
import software.amazon.awssdk.services.ssm.model.GetParameterResponse
import software.amazon.awssdk.services.ssm.model.GetParametersByPathResponse
import software.amazon.awssdk.services.ssm.model.GetParametersResponse
import software.amazon.awssdk.services.ssm.model.PutParameterResponse
import java.util.concurrent.CompletableFuture

/**
 * Gets a parameter without decryption by default.
 */
fun SsmAsyncClient.getParameterAsync(
    name: String,
    withDecryption: Boolean = false,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): CompletableFuture<GetParameterResponse> =
    getParameter(getParameterRequestOf(name, withDecryption, overrideConfiguration))

/**
 * Gets a SecureString parameter with decryption enabled and redacts the value.
 */
fun SsmAsyncClient.getSecureParameterAsync(
    name: String,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): CompletableFuture<AwsSecretValue> =
    getParameterAsync(name, withDecryption = true, overrideConfiguration = overrideConfiguration)
        .thenApply { response ->
            val value = response.parameter()?.value()
                ?: error("Parameter value is not present.")
            awsSecretValueOf(value)
        }

/**
 * Gets up to ten parameters and preserves raw SDK invalid-parameter details.
 */
fun SsmAsyncClient.getParametersAsync(
    names: Collection<String>,
    withDecryption: Boolean = false,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): CompletableFuture<GetParametersResponse> =
    getParameters(getParametersRequestOf(names, withDecryption, overrideConfiguration))

/**
 * Gets one parameter page by path.
 */
fun SsmAsyncClient.getParametersByPathAsync(
    path: String,
    recursive: Boolean? = null,
    withDecryption: Boolean = false,
    maxResults: Int? = null,
    nextToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): CompletableFuture<GetParametersByPathResponse> =
    getParametersByPath(getParametersByPathRequestOf(path, recursive, withDecryption, maxResults, nextToken, overrideConfiguration))

/**
 * Describes one page of parameters.
 */
fun SsmAsyncClient.describeParametersAsync(
    maxResults: Int? = null,
    nextToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): CompletableFuture<DescribeParametersResponse> =
    describeParameters(describeParametersRequestOf(maxResults, nextToken, overrideConfiguration))

/**
 * Puts a SecureString parameter from a redacted value.
 *
 * This mutates AWS-side state and sends the revealed value to SSM as
 * SecureString plaintext. [overwrite] controls whether an existing value may be
 * replaced. Callers remain responsible for IAM/KMS policy and audit boundaries.
 * Do not log or print the revealed value.
 */
fun SsmAsyncClient.putSecureParameterAsync(
    name: String,
    value: AwsSecretValue,
    overwrite: Boolean = false,
    description: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): CompletableFuture<PutParameterResponse> =
    putParameter(putSecureParameterRequestOf(name, value, overwrite, description, overrideConfiguration))

/**
 * Puts a non-secret String parameter.
 */
fun SsmAsyncClient.putStringParameterAsync(
    name: String,
    value: String,
    overwrite: Boolean = false,
    description: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): CompletableFuture<PutParameterResponse> =
    putParameter(putStringParameterRequestOf(name, value, overwrite, description, overrideConfiguration))

/**
 * Puts a non-secret StringList parameter.
 */
fun SsmAsyncClient.putStringListParameterAsync(
    name: String,
    values: Collection<String>,
    overwrite: Boolean = false,
    description: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): CompletableFuture<PutParameterResponse> =
    putParameter(putStringListParameterRequestOf(name, values, overwrite, description, overrideConfiguration))
