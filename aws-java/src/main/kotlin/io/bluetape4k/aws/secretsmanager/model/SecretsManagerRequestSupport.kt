@file:Suppress("TooManyFunctions")

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
 * [GetSecretValueRequest]를 SDK builder 필드만 사용해 구성합니다.
 * 필수 `secretId`와 최종 검증이 필요한 경우 [getSecretValueRequestOf]를 사용하세요.
 */
inline fun getSecretValueRequest(
    builder: GetSecretValueRequest.Builder.() -> Unit,
): GetSecretValueRequest = GetSecretValueRequest.builder()
    .apply(builder)
    .build()

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
        .also { request ->
            request.secretId().requireNotBlank("secretId")
            request.versionId()?.requireNotBlank("versionId")
            request.versionStage()?.requireNotBlank("versionStage")
        }
}
/**
 * 단일 페이지 [BatchGetSecretValueRequest]를 SDK builder 필드만 사용해 구성합니다.
 * 필수 secret ID 목록과 limit 검증이 필요한 경우 [batchGetSecretValueRequestOf]를 사용하세요.
 */
inline fun batchGetSecretValueRequest(
    builder: BatchGetSecretValueRequest.Builder.() -> Unit,
): BatchGetSecretValueRequest = BatchGetSecretValueRequest.builder()
    .apply(builder)
    .build()

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
        .also { request ->
            require(request.secretIdList().isNotEmpty()) { "secretIds must not be empty" }
            require(request.secretIdList().size <= 20) {
                "secretIds size must be less than or equal to 20"
            }
            request.secretIdList().forEach { it.requireNotBlank("secretId") }
            request.maxResults()?.let {
                require(it in 1..20) { "maxResults must be in 1..20" }
            }
            request.nextToken()?.requireNotBlank("nextToken")
        }
}

/**
 * 단일 페이지 [ListSecretsRequest]를 SDK builder 필드만 사용해 구성합니다.
 * limit·token 검증이 필요한 경우 [listSecretsRequestOf]를 사용하세요.
 */
inline fun listSecretsRequest(
    builder: ListSecretsRequest.Builder.() -> Unit,
): ListSecretsRequest = ListSecretsRequest.builder()
    .apply(builder)
    .build()

/**
 * 단일 페이지 [ListSecretsRequest]를 구성합니다.
 */
@Suppress("MagicNumber")
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
        .also { request ->
            request.maxResults()?.let {
                require(it in 1..100) { "maxResults must be in 1..100" }
            }
            request.nextToken()?.requireNotBlank("nextToken")
        }
}

/**
 * [DescribeSecretRequest]를 SDK builder 필드만 사용해 구성합니다.
 * 필수 `secretId`와 최종 검증이 필요한 경우 [describeSecretRequestOf]를 사용하세요.
 */
inline fun describeSecretRequest(
    builder: DescribeSecretRequest.Builder.() -> Unit,
): DescribeSecretRequest = DescribeSecretRequest.builder()
    .apply(builder)
    .build()

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
        .also { it.secretId().requireNotBlank("secretId") }
}

/**
 * 평문을 직접 담을 수 있는 SDK builder로 [CreateSecretRequest]를 구성합니다.
 *
 * raw builder와 최종 SDK 요청은 평문을 보유할 수 있으므로 로그나 진단 출력에 남기지 마세요.
 * 이 함수는 필수 값 검증을 수행하지 않으며, 비밀 값은 [AwsSecretValue]와
 * [createSecretRequestOf] 경로를 우선 사용하세요.
 */
inline fun createSecretRequest(
    builder: CreateSecretRequest.Builder.() -> Unit,
): CreateSecretRequest = CreateSecretRequest.builder()
    .apply(builder)
    .build()

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
        .also { request ->
            request.name().requireNotBlank("name")
            request.secretString().requireNotBlank("secretString")
            request.description()?.requireNotBlank("description")
            request.clientRequestToken()?.requireNotBlank("clientRequestToken")
        }
}

/**
 * 평문을 직접 담을 수 있는 SDK builder로 [PutSecretValueRequest]를 구성합니다.
 *
 * raw builder와 최종 SDK 요청은 평문을 보유할 수 있으므로 로그나 진단 출력에 남기지 마세요.
 * 이 함수는 필수 값 검증을 수행하지 않으며, 비밀 값은 [AwsSecretValue]와
 * [putSecretValueRequestOf] 경로를 우선 사용하세요.
 */
inline fun putSecretValueRequest(
    builder: PutSecretValueRequest.Builder.() -> Unit,
): PutSecretValueRequest = PutSecretValueRequest.builder()
    .apply(builder)
    .build()

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
        .also { request ->
            request.secretId().requireNotBlank("secretId")
            request.secretString().requireNotBlank("secretString")
            request.clientRequestToken()?.requireNotBlank("clientRequestToken")
            request.versionStages().forEach { it.requireNotBlank("versionStage") }
        }
}
