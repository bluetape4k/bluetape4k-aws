package io.bluetape4k.aws.secretsmanager

import io.bluetape4k.aws.secretsmanager.model.batchGetSecretValueRequestOf
import io.bluetape4k.aws.secretsmanager.model.createSecretRequestOf
import io.bluetape4k.aws.secretsmanager.model.describeSecretRequestOf
import io.bluetape4k.aws.secretsmanager.model.getSecretValueRequestOf
import io.bluetape4k.aws.secretsmanager.model.listSecretsRequestOf
import io.bluetape4k.aws.secretsmanager.model.putSecretValueRequestOf
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.secretsmanager.model.BatchGetSecretValueResponse
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretResponse
import software.amazon.awssdk.services.secretsmanager.model.DescribeSecretResponse
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse
import software.amazon.awssdk.services.secretsmanager.model.ListSecretsResponse
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueResponse

/**
 * Gets the raw [GetSecretValueResponse].
 */
fun SecretsManagerClient.getSecretValueResponse(
    secretId: String,
    versionId: String? = null,
    versionStage: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): GetSecretValueResponse =
    getSecretValue(getSecretValueRequestOf(secretId, versionId, versionStage, overrideConfiguration))

/**
 * Gets a secret string as a redacted [AwsSecretValue].
 */
fun SecretsManagerClient.getSecretString(
    secretId: String,
    versionId: String? = null,
    versionStage: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): AwsSecretValue {
    val response = getSecretValueResponse(secretId, versionId, versionStage, overrideConfiguration)
    val secretString = response.secretString()
        ?: error("Secret string is not present for secretId.")
    return awsSecretValueOf(secretString)
}

/**
 * Gets a single page of secrets and preserves raw SDK partial errors.
 */
fun SecretsManagerClient.batchGetSecretValues(
    secretIds: Collection<String>,
    maxResults: Int? = null,
    nextToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): BatchGetSecretValueResponse =
    batchGetSecretValue(batchGetSecretValueRequestOf(secretIds, maxResults, nextToken, overrideConfiguration))

/**
 * Lists one page of Secrets Manager metadata.
 */
fun SecretsManagerClient.listSecrets(
    maxResults: Int? = null,
    nextToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): ListSecretsResponse =
    listSecrets(listSecretsRequestOf(maxResults, nextToken, overrideConfiguration))

/**
 * Describes a secret without reading its secret value.
 */
fun SecretsManagerClient.describeSecret(
    secretId: String,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): DescribeSecretResponse =
    describeSecret(describeSecretRequestOf(secretId, overrideConfiguration))

/**
 * Creates a secret using a redacted value wrapper.
 *
 * This mutates AWS-side state and may create a new secret. Do not log or print
 * the revealed value.
 */
fun SecretsManagerClient.createSecret(
    name: String,
    secretValue: AwsSecretValue,
    description: String? = null,
    clientRequestToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): CreateSecretResponse =
    createSecret(createSecretRequestOf(name, secretValue, description, clientRequestToken, overrideConfiguration))

/**
 * Adds a new secret value version using a redacted value wrapper.
 *
 * This mutates AWS-side state and may change staging labels. Do not log or
 * print the revealed value.
 */
fun SecretsManagerClient.putSecretValue(
    secretId: String,
    secretValue: AwsSecretValue,
    clientRequestToken: String? = null,
    versionStages: Collection<String> = emptyList(),
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): PutSecretValueResponse =
    putSecretValue(putSecretValueRequestOf(secretId, secretValue, clientRequestToken, versionStages, overrideConfiguration))
