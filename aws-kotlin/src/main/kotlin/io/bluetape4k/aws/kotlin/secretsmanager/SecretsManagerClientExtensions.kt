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
 * 원본 보안 값 응답을 가져옵니다.
 */
suspend fun SecretsManagerClient.getSecretValueResponse(
    secretId: String,
    versionId: String? = null,
    versionStage: String? = null,
): GetSecretValueResponse =
    getSecretValue(getSecretValueRequestOf(secretId, versionId, versionStage))

/**
 * 보안 문자열을 값이 가려진 [AwsSecretValue]로 가져옵니다.
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
 * 보안 값 페이지 하나를 가져오고 SDK 원본 부분 오류를 보존합니다.
 */
suspend fun SecretsManagerClient.batchGetSecretValues(
    secretIds: Collection<String>,
    maxResults: Int? = null,
    nextToken: String? = null,
): BatchGetSecretValueResponse =
    batchGetSecretValue(batchGetSecretValueRequestOf(secretIds, maxResults, nextToken))

/**
 * Secrets Manager 메타데이터 페이지 하나를 조회합니다.
 */
suspend fun SecretsManagerClient.listSecrets(
    maxResults: Int? = null,
    nextToken: String? = null,
): ListSecretsResponse =
    listSecrets(listSecretsRequestOf(maxResults, nextToken))

/**
 * 보안 값을 읽지 않고 보안 항목을 설명합니다.
 */
suspend fun SecretsManagerClient.describeSecret(secretId: String): DescribeSecretResponse =
    describeSecret(describeSecretRequestOf(secretId))

/**
 * 값이 가려진 래퍼를 사용해 보안 값을 생성합니다.
 *
 * 이 작업은 AWS 측 상태를 변경하며 새 보안 값을 만들 수 있습니다. 드러낸 값을 로그에 남기거나 출력하지 마세요.
 */
suspend fun SecretsManagerClient.createSecret(
    name: String,
    secretValue: AwsSecretValue,
    description: String? = null,
    clientRequestToken: String? = null,
): CreateSecretResponse =
    createSecret(createSecretRequestOf(name, secretValue, description, clientRequestToken))

/**
 * 값이 가려진 래퍼로 새 보안 값 버전을 추가합니다.
 *
 * 이 작업은 AWS 측 상태를 변경하며 스테이징 레이블을 바꿀 수 있습니다. 드러낸 값을 로그에 남기거나 출력하지 마세요.
 */
suspend fun SecretsManagerClient.putSecretValue(
    secretId: String,
    secretValue: AwsSecretValue,
    clientRequestToken: String? = null,
    versionStages: Collection<String> = emptyList(),
): PutSecretValueResponse =
    putSecretValue(putSecretValueRequestOf(secretId, secretValue, clientRequestToken, versionStages))
