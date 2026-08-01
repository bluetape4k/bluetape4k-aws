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
 * 비동기 클라이언트에서 원본 보안 값 응답을 가져와 완료를 기다립니다.
 */
suspend fun SecretsManagerAsyncClient.getSecretValueResponse(
    secretId: String,
    versionId: String? = null,
    versionStage: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): GetSecretValueResponse =
    getSecretValueAsync(secretId, versionId, versionStage, overrideConfiguration).await()

/**
 * 보안 문자열을 값이 가려진 [AwsSecretValue]로 가져와 완료를 기다립니다.
 */
suspend fun SecretsManagerAsyncClient.getSecretString(
    secretId: String,
    versionId: String? = null,
    versionStage: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): AwsSecretValue =
    getSecretStringAsync(secretId, versionId, versionStage, overrideConfiguration).await()

/**
 * 배치 페이지 하나를 가져와 완료를 기다립니다.
 */
suspend fun SecretsManagerAsyncClient.batchGetSecretValues(
    secretIds: Collection<String>,
    maxResults: Int? = null,
    nextToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): BatchGetSecretValueResponse =
    batchGetSecretValuesAsync(secretIds, maxResults, nextToken, overrideConfiguration).await()

/**
 * 메타데이터 페이지 하나를 조회하고 완료를 기다립니다.
 */
suspend fun SecretsManagerAsyncClient.listSecrets(
    maxResults: Int? = null,
    nextToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): ListSecretsResponse =
    listSecretsAsync(maxResults, nextToken, overrideConfiguration).await()

/**
 * 보안 값을 설명하고 완료를 기다립니다.
 */
suspend fun SecretsManagerAsyncClient.describeSecret(
    secretId: String,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): DescribeSecretResponse =
    describeSecretAsync(secretId, overrideConfiguration).await()

/**
 * 값이 가려진 래퍼를 사용해 보안 값을 생성하고 완료를 기다립니다.
 *
 * 이 작업은 AWS 측 상태를 변경하며 새 보안 값을 만들 수 있습니다. 드러낸 값을 로그에 남기거나 출력하지 마세요.
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
 * 값이 가려진 래퍼로 새 보안 값 버전을 추가하고 완료를 기다립니다.
 *
 * 이 작업은 AWS 측 상태를 변경하며 스테이징 레이블을 바꿀 수 있습니다. 드러낸 값을 로그에 남기거나 출력하지 마세요.
 */
suspend fun SecretsManagerAsyncClient.putSecretValue(
    secretId: String,
    secretValue: AwsSecretValue,
    clientRequestToken: String? = null,
    versionStages: Collection<String> = emptyList(),
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): PutSecretValueResponse =
    putSecretValueAsync(secretId, secretValue, clientRequestToken, versionStages, overrideConfiguration).await()
