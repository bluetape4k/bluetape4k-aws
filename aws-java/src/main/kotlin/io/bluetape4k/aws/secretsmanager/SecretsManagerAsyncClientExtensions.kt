package io.bluetape4k.aws.secretsmanager

import io.bluetape4k.aws.secretsmanager.model.batchGetSecretValueRequestOf
import io.bluetape4k.aws.secretsmanager.model.createSecretRequestOf
import io.bluetape4k.aws.secretsmanager.model.describeSecretRequestOf
import io.bluetape4k.aws.secretsmanager.model.getSecretValueRequestOf
import io.bluetape4k.aws.secretsmanager.model.listSecretsRequestOf
import io.bluetape4k.aws.secretsmanager.model.putSecretValueRequestOf
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.services.secretsmanager.SecretsManagerAsyncClient
import software.amazon.awssdk.services.secretsmanager.model.BatchGetSecretValueResponse
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretResponse
import software.amazon.awssdk.services.secretsmanager.model.DescribeSecretResponse
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse
import software.amazon.awssdk.services.secretsmanager.model.ListSecretsResponse
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueResponse
import java.util.concurrent.CompletableFuture

/**
 * Gets the raw [GetSecretValueResponse] asynchronously.
 */
fun SecretsManagerAsyncClient.getSecretValueAsync(
    secretId: String,
    versionId: String? = null,
    versionStage: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): CompletableFuture<GetSecretValueResponse> =
    getSecretValue(getSecretValueRequestOf(secretId, versionId, versionStage, overrideConfiguration))

/**
 * Gets a secret string as a redacted [AwsSecretValue] asynchronously.
 */
fun SecretsManagerAsyncClient.getSecretStringAsync(
    secretId: String,
    versionId: String? = null,
    versionStage: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): CompletableFuture<AwsSecretValue> =
    getSecretValueAsync(secretId, versionId, versionStage, overrideConfiguration)
        .thenApply { response ->
            val secretString = response.secretString()
                ?: error("Secret string is not present for secretId.")
            awsSecretValueOf(secretString)
        }

/**
 * Gets a single page of secrets and preserves raw SDK partial errors.
 */
fun SecretsManagerAsyncClient.batchGetSecretValuesAsync(
    secretIds: Collection<String>,
    maxResults: Int? = null,
    nextToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): CompletableFuture<BatchGetSecretValueResponse> =
    batchGetSecretValue(batchGetSecretValueRequestOf(secretIds, maxResults, nextToken, overrideConfiguration))

/**
 * Lists one page of Secrets Manager metadata asynchronously.
 */
fun SecretsManagerAsyncClient.listSecretsAsync(
    maxResults: Int? = null,
    nextToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): CompletableFuture<ListSecretsResponse> =
    listSecrets(listSecretsRequestOf(maxResults, nextToken, overrideConfiguration))

/**
 * Describes a secret without reading its secret value asynchronously.
 */
fun SecretsManagerAsyncClient.describeSecretAsync(
    secretId: String,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): CompletableFuture<DescribeSecretResponse> =
    describeSecret(describeSecretRequestOf(secretId, overrideConfiguration))

/**
 * Creates a secret using a redacted value wrapper asynchronously.
 *
 * This mutates AWS-side state and may create a new secret. Do not log or print
 * the revealed value.
 */
fun SecretsManagerAsyncClient.createSecretAsync(
    name: String,
    secretValue: AwsSecretValue,
    description: String? = null,
    clientRequestToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): CompletableFuture<CreateSecretResponse> =
    createSecret(createSecretRequestOf(name, secretValue, description, clientRequestToken, overrideConfiguration))

/**
 * Adds a new secret value version using a redacted value wrapper asynchronously.
 *
 * This mutates AWS-side state and may change staging labels. Do not log or
 * print the revealed value.
 */
fun SecretsManagerAsyncClient.putSecretValueAsync(
    secretId: String,
    secretValue: AwsSecretValue,
    clientRequestToken: String? = null,
    versionStages: Collection<String> = emptyList(),
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): CompletableFuture<PutSecretValueResponse> =
    putSecretValue(putSecretValueRequestOf(secretId, secretValue, clientRequestToken, versionStages, overrideConfiguration))
