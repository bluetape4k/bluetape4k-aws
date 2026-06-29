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
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.DescribeParametersResponse
import software.amazon.awssdk.services.ssm.model.GetParameterResponse
import software.amazon.awssdk.services.ssm.model.GetParametersByPathResponse
import software.amazon.awssdk.services.ssm.model.GetParametersResponse
import software.amazon.awssdk.services.ssm.model.PutParameterResponse

/**
 * Gets a parameter without decryption by default.
 */
fun SsmClient.getParameter(
    name: String,
    withDecryption: Boolean = false,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): GetParameterResponse =
    getParameter(getParameterRequestOf(name, withDecryption, overrideConfiguration))

/**
 * Gets a SecureString parameter with decryption enabled and redacts the value.
 */
fun SsmClient.getSecureParameter(
    name: String,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): AwsSecretValue {
    val value = getParameter(name, withDecryption = true, overrideConfiguration = overrideConfiguration)
        .parameter()
        ?.value()
        ?: error("Parameter value is not present.")
    return awsSecretValueOf(value)
}

/**
 * Gets up to ten parameters and preserves raw SDK invalid-parameter details.
 */
fun SsmClient.getParameters(
    names: Collection<String>,
    withDecryption: Boolean = false,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): GetParametersResponse =
    getParameters(getParametersRequestOf(names, withDecryption, overrideConfiguration))

/**
 * Gets one parameter page by path.
 */
fun SsmClient.getParametersByPath(
    path: String,
    recursive: Boolean? = null,
    withDecryption: Boolean = false,
    maxResults: Int? = null,
    nextToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): GetParametersByPathResponse =
    getParametersByPath(getParametersByPathRequestOf(path, recursive, withDecryption, maxResults, nextToken, overrideConfiguration))

/**
 * Describes one page of parameters.
 */
fun SsmClient.describeParameters(
    maxResults: Int? = null,
    nextToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): DescribeParametersResponse =
    describeParameters(describeParametersRequestOf(maxResults, nextToken, overrideConfiguration))

/**
 * Puts a SecureString parameter from a redacted value.
 *
 * This mutates AWS-side state and sends the revealed value to SSM as
 * SecureString plaintext. [overwrite] controls whether an existing value may be
 * replaced. Callers remain responsible for IAM/KMS policy and audit boundaries.
 * Do not log or print the revealed value.
 */
fun SsmClient.putSecureParameter(
    name: String,
    value: AwsSecretValue,
    overwrite: Boolean = false,
    description: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): PutParameterResponse =
    putParameter(putSecureParameterRequestOf(name, value, overwrite, description, overrideConfiguration))

/**
 * Puts a non-secret String parameter.
 */
fun SsmClient.putStringParameter(
    name: String,
    value: String,
    overwrite: Boolean = false,
    description: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): PutParameterResponse =
    putParameter(putStringParameterRequestOf(name, value, overwrite, description, overrideConfiguration))

/**
 * Puts a non-secret StringList parameter.
 */
fun SsmClient.putStringListParameter(
    name: String,
    values: Collection<String>,
    overwrite: Boolean = false,
    description: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): PutParameterResponse =
    putParameter(putStringListParameterRequestOf(name, values, overwrite, description, overrideConfiguration))
