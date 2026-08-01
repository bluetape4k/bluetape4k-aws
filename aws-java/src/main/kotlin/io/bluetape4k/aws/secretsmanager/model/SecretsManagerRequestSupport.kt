package io.bluetape4k.aws.secretsmanager.model

import io.bluetape4k.aws.secretsmanager.AwsSecretValue
import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.services.secretsmanager.model.BatchGetSecretValueRequest
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest
import software.amazon.awssdk.services.secretsmanager.model.DescribeSecretRequest
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest
import software.amazon.awssdk.services.secretsmanager.model.ListSecretsRequest
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueRequest

/**
 * [GetSecretValueRequest]를 구성합니다.
 */
inline fun getSecretValueRequestOf(
    secretId: String,
    versionId: String? = null,
    versionStage: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: GetSecretValueRequest.Builder.() -> Unit = {},
): GetSecretValueRequest {
    secretId.requireNotBlank("secretId")
    versionId?.requireNotBlank("versionId")
    versionStage?.requireNotBlank("versionStage")

    return GetSecretValueRequest.builder()
        .secretId(secretId)
        .apply {
            versionId?.let { versionId(it) }
            versionStage?.let { versionStage(it) }
            overrideConfiguration?.let { overrideConfiguration(it) }
            builder()
        }
        .build()
}

/**
 * 단일 페이지 [BatchGetSecretValueRequest]를 구성합니다.
 */
inline fun batchGetSecretValueRequestOf(
    secretIds: Collection<String>,
    maxResults: Int? = null,
    nextToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: BatchGetSecretValueRequest.Builder.() -> Unit = {},
): BatchGetSecretValueRequest {
    require(secretIds.isNotEmpty()) { "secretIds must not be empty" }
    require(secretIds.size <= 20) { "secretIds size must be less than or equal to 20" }
    secretIds.forEach { it.requireNotBlank("secretId") }
    nextToken?.requireNotBlank("nextToken")

    return BatchGetSecretValueRequest.builder()
        .secretIdList(secretIds)
        .apply {
            maxResults?.let { maxResults(it) }
            nextToken?.let { nextToken(it) }
            overrideConfiguration?.let { overrideConfiguration(it) }
            builder()
        }
        .build()
}

/**
 * 단일 페이지 [ListSecretsRequest]를 구성합니다.
 */
inline fun listSecretsRequestOf(
    maxResults: Int? = null,
    nextToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: ListSecretsRequest.Builder.() -> Unit = {},
): ListSecretsRequest {
    nextToken?.requireNotBlank("nextToken")

    return ListSecretsRequest.builder()
        .apply {
            maxResults?.let { maxResults(it) }
            nextToken?.let { nextToken(it) }
            overrideConfiguration?.let { overrideConfiguration(it) }
            builder()
        }
        .build()
}

/**
 * [DescribeSecretRequest]를 구성합니다.
 */
inline fun describeSecretRequestOf(
    secretId: String,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: DescribeSecretRequest.Builder.() -> Unit = {},
): DescribeSecretRequest {
    secretId.requireNotBlank("secretId")

    return DescribeSecretRequest.builder()
        .secretId(secretId)
        .apply {
            overrideConfiguration?.let { overrideConfiguration(it) }
            builder()
        }
        .build()
}

/**
 * 값이 가려진 보안 값으로 [CreateSecretRequest]를 구성합니다.
 */
inline fun createSecretRequestOf(
    name: String,
    secretValue: AwsSecretValue,
    description: String? = null,
    clientRequestToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: CreateSecretRequest.Builder.() -> Unit = {},
): CreateSecretRequest {
    name.requireNotBlank("name")
    description?.requireNotBlank("description")
    clientRequestToken?.requireNotBlank("clientRequestToken")

    return CreateSecretRequest.builder()
        .name(name)
        .secretString(secretValue.reveal())
        .apply {
            description?.let { description(it) }
            clientRequestToken?.let { clientRequestToken(it) }
            overrideConfiguration?.let { overrideConfiguration(it) }
            builder()
        }
        .build()
}

/**
 * 값이 가려진 보안 값으로 [PutSecretValueRequest]를 구성합니다.
 */
inline fun putSecretValueRequestOf(
    secretId: String,
    secretValue: AwsSecretValue,
    clientRequestToken: String? = null,
    versionStages: Collection<String> = emptyList(),
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: PutSecretValueRequest.Builder.() -> Unit = {},
): PutSecretValueRequest {
    secretId.requireNotBlank("secretId")
    clientRequestToken?.requireNotBlank("clientRequestToken")
    versionStages.forEach { it.requireNotBlank("versionStage") }

    return PutSecretValueRequest.builder()
        .secretId(secretId)
        .secretString(secretValue.reveal())
        .apply {
            clientRequestToken?.let { clientRequestToken(it) }
            if (versionStages.isNotEmpty()) {
                versionStages(versionStages)
            }
            overrideConfiguration?.let { overrideConfiguration(it) }
            builder()
        }
        .build()
}
