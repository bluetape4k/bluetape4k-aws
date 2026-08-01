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
 * [GetSecretValueRequest]를 구성합니다.
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
 * 단일 페이지 [BatchGetSecretValueRequest]를 구성합니다.
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
 * 단일 페이지 [ListSecretsRequest]를 구성합니다.
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
 * [DescribeSecretRequest]를 구성합니다.
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
 * 값이 가려진 래퍼에서 [CreateSecretRequest]를 구성합니다.
 *
 * 이 경계에서 드러낸 값을 AWS SDK 요청에 담습니다. 이 요청은 AWS 측 상태를 생성할 수 있으므로
 * 호출자는 감사 및 교체 정책을 명확히 유지해야 합니다.
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
 * 값이 가려진 래퍼에서 [PutSecretValueRequest]를 구성합니다.
 *
 * 이 경계에서 드러낸 값을 AWS SDK 요청에 담습니다. 이 요청은 새 보안 값 버전을 만들거나
 * 스테이징 레이블을 변경할 수 있습니다.
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
