package io.bluetape4k.aws.secretsmanager

import kotlinx.coroutines.future.await
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.services.secretsmanager.SecretsManagerAsyncClient
import software.amazon.awssdk.services.secretsmanager.model.BatchGetSecretValueResponse
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretResponse
import software.amazon.awssdk.services.secretsmanager.model.DescribeSecretResponse
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse
import software.amazon.awssdk.services.secretsmanager.model.ListSecretsResponse
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueResponse

/**
 * Gets the raw secret response from an async client and awaits completion.
 */
suspend fun SecretsManagerAsyncClient.getSecretValueResponse(
    secretId: String,
    versionId: String? = null,
    versionStage: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): GetSecretValueResponse =
    getSecretValueAsync(secretId, versionId, versionStage, overrideConfiguration).await()

/**
 * Gets a secret string as a redacted [AwsSecretValue] and awaits completion.
 */
suspend fun SecretsManagerAsyncClient.getSecretString(
    secretId: String,
    versionId: String? = null,
    versionStage: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): AwsSecretValue =
    getSecretStringAsync(secretId, versionId, versionStage, overrideConfiguration).await()

/**
 * Gets one batch page and awaits completion.
 */
suspend fun SecretsManagerAsyncClient.batchGetSecretValues(
    secretIds: Collection<String>,
    maxResults: Int? = null,
    nextToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): BatchGetSecretValueResponse =
    batchGetSecretValuesAsync(secretIds, maxResults, nextToken, overrideConfiguration).await()

/**
 * Lists one metadata page and awaits completion.
 */
suspend fun SecretsManagerAsyncClient.listSecrets(
    maxResults: Int? = null,
    nextToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): ListSecretsResponse =
    listSecretsAsync(maxResults, nextToken, overrideConfiguration).await()

/**
 * Describes a secret and awaits completion.
 */
suspend fun SecretsManagerAsyncClient.describeSecret(
    secretId: String,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): DescribeSecretResponse =
    describeSecretAsync(secretId, overrideConfiguration).await()

/**
 * Creates a secret using a redacted value wrapper and awaits completion.
 *
 * This mutates AWS-side state and may create a new secret. Do not log or print
 * the revealed value.
 */
suspend fun SecretsManagerAsyncClient.createSecret(
    name: String,
    secretValue: AwsSecretValue,
    description: String? = null,
    clientRequestToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): CreateSecretResponse =
    createSecretAsync(name, secretValue, description, clientRequestToken, overrideConfiguration).await()

/**
 * Adds a new secret value version using a redacted value wrapper and awaits completion.
 *
 * This mutates AWS-side state and may change staging labels. Do not log or
 * print the revealed value.
 */
suspend fun SecretsManagerAsyncClient.putSecretValue(
    secretId: String,
    secretValue: AwsSecretValue,
    clientRequestToken: String? = null,
    versionStages: Collection<String> = emptyList(),
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): PutSecretValueResponse =
    putSecretValueAsync(secretId, secretValue, clientRequestToken, versionStages, overrideConfiguration).await()
