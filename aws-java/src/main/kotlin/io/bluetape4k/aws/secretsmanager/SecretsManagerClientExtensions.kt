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
 * 원본 [GetSecretValueResponse]를 가져옵니다.
 */
fun SecretsManagerClient.getSecretValueResponse(
    secretId: String,
    versionId: String? = null,
    versionStage: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): GetSecretValueResponse =
    getSecretValue(getSecretValueRequestOf(secretId, versionId, versionStage, overrideConfiguration))

/**
 * 보안 문자열을 값이 가려진 [AwsSecretValue]로 가져옵니다.
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
 * 보안 값 페이지 하나를 가져오고 SDK 원본 부분 오류를 보존합니다.
 */
fun SecretsManagerClient.batchGetSecretValues(
    secretIds: Collection<String>,
    maxResults: Int? = null,
    nextToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): BatchGetSecretValueResponse =
    batchGetSecretValue(batchGetSecretValueRequestOf(secretIds, maxResults, nextToken, overrideConfiguration))

/**
 * Secrets Manager 메타데이터 페이지 하나를 조회합니다.
 */
fun SecretsManagerClient.listSecrets(
    maxResults: Int? = null,
    nextToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): ListSecretsResponse =
    listSecrets(listSecretsRequestOf(maxResults, nextToken, overrideConfiguration))

/**
 * 보안 값을 읽지 않고 보안 항목을 설명합니다.
 */
fun SecretsManagerClient.describeSecret(
    secretId: String,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): DescribeSecretResponse =
    describeSecret(describeSecretRequestOf(secretId, overrideConfiguration))

/**
 * 값이 가려진 래퍼를 사용해 보안 값을 생성합니다.
 *
 * 이 작업은 AWS 측 상태를 변경하며 새 보안 값을 만들 수 있습니다. 드러낸 값을 로그에 남기거나 출력하지 마세요.
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
 * 값이 가려진 래퍼로 새 보안 값 버전을 추가합니다.
 *
 * 이 작업은 AWS 측 상태를 변경하며 스테이징 레이블을 바꿀 수 있습니다. 드러낸 값을 로그에 남기거나 출력하지 마세요.
 */
fun SecretsManagerClient.putSecretValue(
    secretId: String,
    secretValue: AwsSecretValue,
    clientRequestToken: String? = null,
    versionStages: Collection<String> = emptyList(),
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): PutSecretValueResponse =
    putSecretValue(putSecretValueRequestOf(secretId, secretValue, clientRequestToken, versionStages, overrideConfiguration))
