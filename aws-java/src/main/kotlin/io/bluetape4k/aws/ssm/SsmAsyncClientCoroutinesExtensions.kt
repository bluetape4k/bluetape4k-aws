package io.bluetape4k.aws.ssm

import io.bluetape4k.aws.secretsmanager.AwsSecretValue
import kotlinx.coroutines.future.await
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.services.ssm.SsmAsyncClient
import software.amazon.awssdk.services.ssm.model.DescribeParametersResponse
import software.amazon.awssdk.services.ssm.model.GetParameterResponse
import software.amazon.awssdk.services.ssm.model.GetParametersByPathResponse
import software.amazon.awssdk.services.ssm.model.GetParametersResponse
import software.amazon.awssdk.services.ssm.model.PutParameterResponse

/**
 * 기본적으로 복호화 없이 파라미터를 가져와 완료를 기다립니다.
 */
suspend fun SsmAsyncClient.getParameter(
    name: String,
    withDecryption: Boolean = false,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): GetParameterResponse =
    getParameterAsync(name, withDecryption, overrideConfiguration).await()

/**
 * 복호화를 활성화해 SecureString 파라미터를 가져와 완료를 기다립니다.
 */
suspend fun SsmAsyncClient.getSecureParameter(
    name: String,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): AwsSecretValue =
    getSecureParameterAsync(name, overrideConfiguration).await()

/**
 * 최대 10개의 파라미터를 가져와 완료를 기다립니다.
 */
suspend fun SsmAsyncClient.getParameters(
    names: Collection<String>,
    withDecryption: Boolean = false,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): GetParametersResponse =
    getParametersAsync(names, withDecryption, overrideConfiguration).await()

/**
 * 경로에 해당하는 파라미터 페이지 하나를 가져와 완료를 기다립니다.
 */
suspend fun SsmAsyncClient.getParametersByPath(
    path: String,
    recursive: Boolean? = null,
    withDecryption: Boolean = false,
    maxResults: Int? = null,
    nextToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): GetParametersByPathResponse =
    getParametersByPathAsync(path, recursive, withDecryption, maxResults, nextToken, overrideConfiguration).await()

/**
 * 파라미터 페이지 하나를 설명하고 완료를 기다립니다.
 */
suspend fun SsmAsyncClient.describeParameters(
    maxResults: Int? = null,
    nextToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): DescribeParametersResponse =
    describeParametersAsync(maxResults, nextToken, overrideConfiguration).await()

/**
 * 값이 가려진 래퍼에서 SecureString 파라미터를 저장하고 완료를 기다립니다.
 *
 * 이 작업은 AWS 측 상태를 변경하며 드러낸 값을 SecureString 평문으로 SSM에 전송합니다.
 * [overwrite]는 기존 값을 대체할 수 있는지 제어합니다. IAM/KMS 정책과 감사 경계는 호출자가 책임집니다.
 * 드러낸 값을 로그에 남기거나 출력하지 마세요.
 */
suspend fun SsmAsyncClient.putSecureParameter(
    name: String,
    value: AwsSecretValue,
    overwrite: Boolean = false,
    description: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): PutParameterResponse =
    putSecureParameterAsync(name, value, overwrite, description, overrideConfiguration).await()

/**
 * 비밀이 아닌 String 파라미터를 저장하고 완료를 기다립니다.
 */
suspend fun SsmAsyncClient.putStringParameter(
    name: String,
    value: String,
    overwrite: Boolean = false,
    description: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): PutParameterResponse =
    putStringParameterAsync(name, value, overwrite, description, overrideConfiguration).await()

/**
 * 비밀이 아닌 StringList 파라미터를 저장하고 완료를 기다립니다.
 */
suspend fun SsmAsyncClient.putStringListParameter(
    name: String,
    values: Collection<String>,
    overwrite: Boolean = false,
    description: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): PutParameterResponse =
    putStringListParameterAsync(name, values, overwrite, description, overrideConfiguration).await()
