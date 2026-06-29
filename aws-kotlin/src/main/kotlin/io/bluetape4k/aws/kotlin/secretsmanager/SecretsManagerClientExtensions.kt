package io.bluetape4k.aws.kotlin.secretsmanager

import aws.sdk.kotlin.services.secretsmanager.SecretsManagerClient
import aws.sdk.kotlin.services.secretsmanager.batchGetSecretValue
import aws.sdk.kotlin.services.secretsmanager.createSecret
import aws.sdk.kotlin.services.secretsmanager.describeSecret
import aws.sdk.kotlin.services.secretsmanager.getSecretValue
import aws.sdk.kotlin.services.secretsmanager.listSecrets
import aws.sdk.kotlin.services.secretsmanager.model.BatchGetSecretValueResponse
import aws.sdk.kotlin.services.secretsmanager.model.CreateSecretResponse
import aws.sdk.kotlin.services.secretsmanager.model.DescribeSecretResponse
import aws.sdk.kotlin.services.secretsmanager.model.GetSecretValueResponse
import aws.sdk.kotlin.services.secretsmanager.model.ListSecretsResponse
import aws.sdk.kotlin.services.secretsmanager.model.PutSecretValueResponse
import aws.sdk.kotlin.services.secretsmanager.putSecretValue
import io.bluetape4k.aws.kotlin.secretsmanager.model.batchGetSecretValueRequestOf
import io.bluetape4k.aws.kotlin.secretsmanager.model.createSecretRequestOf
import io.bluetape4k.aws.kotlin.secretsmanager.model.describeSecretRequestOf
import io.bluetape4k.aws.kotlin.secretsmanager.model.getSecretValueRequestOf
import io.bluetape4k.aws.kotlin.secretsmanager.model.listSecretsRequestOf
import io.bluetape4k.aws.kotlin.secretsmanager.model.putSecretValueRequestOf

/**
 * Gets the raw secret response.
 */
suspend fun SecretsManagerClient.getSecretValueResponse(
    secretId: String,
    versionId: String? = null,
    versionStage: String? = null,
): GetSecretValueResponse =
    getSecretValue(getSecretValueRequestOf(secretId, versionId, versionStage))

/**
 * Gets a secret string as a redacted [AwsSecretValue].
 */
suspend fun SecretsManagerClient.getSecretString(
    secretId: String,
    versionId: String? = null,
    versionStage: String? = null,
): AwsSecretValue {
    val secretString = getSecretValueResponse(secretId, versionId, versionStage).secretString
        ?: error("Secret string is not present for secretId.")
    return awsSecretValueOf(secretString)
}

/**
 * Gets a single page of secrets and preserves raw SDK partial errors.
 */
suspend fun SecretsManagerClient.batchGetSecretValues(
    secretIds: Collection<String>,
    maxResults: Int? = null,
    nextToken: String? = null,
): BatchGetSecretValueResponse =
    batchGetSecretValue(batchGetSecretValueRequestOf(secretIds, maxResults, nextToken))

/**
 * Lists one page of Secrets Manager metadata.
 */
suspend fun SecretsManagerClient.listSecrets(
    maxResults: Int? = null,
    nextToken: String? = null,
): ListSecretsResponse =
    listSecrets(listSecretsRequestOf(maxResults, nextToken))

/**
 * Describes a secret without reading its secret value.
 */
suspend fun SecretsManagerClient.describeSecret(secretId: String): DescribeSecretResponse =
    describeSecret(describeSecretRequestOf(secretId))

/**
 * Creates a secret using a redacted value wrapper.
 *
 * This mutates AWS-side state and may create a new secret. Do not log or print
 * the revealed value.
 */
suspend fun SecretsManagerClient.createSecret(
    name: String,
    secretValue: AwsSecretValue,
    description: String? = null,
    clientRequestToken: String? = null,
): CreateSecretResponse =
    createSecret(createSecretRequestOf(name, secretValue, description, clientRequestToken))

/**
 * Adds a new secret value version using a redacted value wrapper.
 *
 * This mutates AWS-side state and may change staging labels. Do not log or
 * print the revealed value.
 */
suspend fun SecretsManagerClient.putSecretValue(
    secretId: String,
    secretValue: AwsSecretValue,
    clientRequestToken: String? = null,
    versionStages: Collection<String> = emptyList(),
): PutSecretValueResponse =
    putSecretValue(putSecretValueRequestOf(secretId, secretValue, clientRequestToken, versionStages))
