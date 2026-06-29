package io.bluetape4k.aws.kotlin.secretsmanager.model

import aws.sdk.kotlin.services.secretsmanager.model.BatchGetSecretValueRequest
import aws.sdk.kotlin.services.secretsmanager.model.CreateSecretRequest
import aws.sdk.kotlin.services.secretsmanager.model.DescribeSecretRequest
import aws.sdk.kotlin.services.secretsmanager.model.GetSecretValueRequest
import aws.sdk.kotlin.services.secretsmanager.model.ListSecretsRequest
import aws.sdk.kotlin.services.secretsmanager.model.PutSecretValueRequest
import io.bluetape4k.aws.kotlin.secretsmanager.AwsSecretValue
import io.bluetape4k.support.requireNotBlank

/**
 * Builds a [GetSecretValueRequest].
 */
inline fun getSecretValueRequestOf(
    secretId: String,
    versionId: String? = null,
    versionStage: String? = null,
    crossinline builder: GetSecretValueRequest.Builder.() -> Unit = {},
): GetSecretValueRequest {
    secretId.requireNotBlank("secretId")
    versionId?.requireNotBlank("versionId")
    versionStage?.requireNotBlank("versionStage")

    return GetSecretValueRequest {
        this.secretId = secretId
        this.versionId = versionId
        this.versionStage = versionStage
        builder()
    }
}

/**
 * Builds a single-page [BatchGetSecretValueRequest].
 */
inline fun batchGetSecretValueRequestOf(
    secretIds: Collection<String>,
    maxResults: Int? = null,
    nextToken: String? = null,
    crossinline builder: BatchGetSecretValueRequest.Builder.() -> Unit = {},
): BatchGetSecretValueRequest {
    require(secretIds.isNotEmpty()) { "secretIds must not be empty" }
    require(secretIds.size <= 20) { "secretIds size must be less than or equal to 20" }
    secretIds.forEach { it.requireNotBlank("secretId") }
    nextToken?.requireNotBlank("nextToken")

    return BatchGetSecretValueRequest {
        this.secretIdList = secretIds.toList()
        this.maxResults = maxResults
        this.nextToken = nextToken
        builder()
    }
}

/**
 * Builds a single-page [ListSecretsRequest].
 */
inline fun listSecretsRequestOf(
    maxResults: Int? = null,
    nextToken: String? = null,
    crossinline builder: ListSecretsRequest.Builder.() -> Unit = {},
): ListSecretsRequest {
    nextToken?.requireNotBlank("nextToken")

    return ListSecretsRequest {
        this.maxResults = maxResults
        this.nextToken = nextToken
        builder()
    }
}

/**
 * Builds a [DescribeSecretRequest].
 */
inline fun describeSecretRequestOf(
    secretId: String,
    crossinline builder: DescribeSecretRequest.Builder.() -> Unit = {},
): DescribeSecretRequest {
    secretId.requireNotBlank("secretId")

    return DescribeSecretRequest {
        this.secretId = secretId
        builder()
    }
}

/**
 * Builds a [CreateSecretRequest] from a redacted value.
 *
 * The revealed value is placed into the AWS SDK request at this boundary. This
 * request may create AWS-side state, so callers should keep audit and rotation
 * policy explicit.
 */
inline fun createSecretRequestOf(
    name: String,
    secretValue: AwsSecretValue,
    description: String? = null,
    clientRequestToken: String? = null,
    crossinline builder: CreateSecretRequest.Builder.() -> Unit = {},
): CreateSecretRequest {
    name.requireNotBlank("name")
    description?.requireNotBlank("description")
    clientRequestToken?.requireNotBlank("clientRequestToken")

    return CreateSecretRequest {
        this.name = name
        this.secretString = secretValue.reveal()
        this.description = description
        this.clientRequestToken = clientRequestToken
        builder()
    }
}

/**
 * Builds a [PutSecretValueRequest] from a redacted value.
 *
 * The revealed value is placed into the AWS SDK request at this boundary. This
 * request may create a new secret version or change staging labels.
 */
inline fun putSecretValueRequestOf(
    secretId: String,
    secretValue: AwsSecretValue,
    clientRequestToken: String? = null,
    versionStages: Collection<String> = emptyList(),
    crossinline builder: PutSecretValueRequest.Builder.() -> Unit = {},
): PutSecretValueRequest {
    secretId.requireNotBlank("secretId")
    clientRequestToken?.requireNotBlank("clientRequestToken")
    versionStages.forEach { it.requireNotBlank("versionStage") }

    return PutSecretValueRequest {
        this.secretId = secretId
        this.secretString = secretValue.reveal()
        this.clientRequestToken = clientRequestToken
        this.versionStages = versionStages.toList()
        builder()
    }
}
