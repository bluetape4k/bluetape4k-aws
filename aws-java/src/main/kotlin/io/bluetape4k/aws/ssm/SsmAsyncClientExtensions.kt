package io.bluetape4k.aws.ssm

import io.bluetape4k.aws.secretsmanager.AwsSecretValue
import io.bluetape4k.aws.secretsmanager.awsSecretValueOf
import io.bluetape4k.aws.ssm.model.describeParametersRequestOf
import io.bluetape4k.aws.ssm.model.getParameterRequestOf
import io.bluetape4k.aws.ssm.model.getParametersByPathRequestOf
import io.bluetape4k.aws.ssm.model.getParametersRequestOf
import io.bluetape4k.aws.ssm.model.putSecureParameterRequestOf
import io.bluetape4k.aws.ssm.model.putStringListParameterRequestOf
import io.bluetape4k.aws.ssm.model.putStringParameterRequestOf
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.services.ssm.SsmAsyncClient
import software.amazon.awssdk.services.ssm.model.DescribeParametersResponse
import software.amazon.awssdk.services.ssm.model.GetParameterResponse
import software.amazon.awssdk.services.ssm.model.GetParametersByPathResponse
import software.amazon.awssdk.services.ssm.model.GetParametersResponse
import software.amazon.awssdk.services.ssm.model.PutParameterResponse
import java.util.concurrent.CompletableFuture

/**
 * 기본적으로 복호화 없이 파라미터를 가져옵니다.
 */
fun SsmAsyncClient.getParameterAsync(
    name: String,
    withDecryption: Boolean = false,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): CompletableFuture<GetParameterResponse> =
    getParameter(getParameterRequestOf(name, withDecryption, overrideConfiguration))

/**
 * 복호화를 활성화해 SecureString 파라미터를 가져오고 값을 가립니다.
 */
fun SsmAsyncClient.getSecureParameterAsync(
    name: String,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): CompletableFuture<AwsSecretValue> =
    getParameterAsync(name, withDecryption = true, overrideConfiguration = overrideConfiguration)
        .thenApply { response ->
            val value = response.parameter()?.value()
                ?: error("Parameter value is not present.")
            awsSecretValueOf(value)
        }

/**
 * 최대 10개의 파라미터를 가져오고 SDK 원본의 잘못된 파라미터 상세 정보를 보존합니다.
 */
fun SsmAsyncClient.getParametersAsync(
    names: Collection<String>,
    withDecryption: Boolean = false,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): CompletableFuture<GetParametersResponse> =
    getParameters(getParametersRequestOf(names, withDecryption, overrideConfiguration))

/**
 * 경로에 해당하는 파라미터 페이지 하나를 가져옵니다.
 */
fun SsmAsyncClient.getParametersByPathAsync(
    path: String,
    recursive: Boolean? = null,
    withDecryption: Boolean = false,
    maxResults: Int? = null,
    nextToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): CompletableFuture<GetParametersByPathResponse> =
    getParametersByPath(getParametersByPathRequestOf(path, recursive, withDecryption, maxResults, nextToken, overrideConfiguration))

/**
 * 파라미터 페이지 하나를 설명합니다.
 */
fun SsmAsyncClient.describeParametersAsync(
    maxResults: Int? = null,
    nextToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): CompletableFuture<DescribeParametersResponse> =
    describeParameters(describeParametersRequestOf(maxResults, nextToken, overrideConfiguration))

/**
 * 값이 가려진 래퍼에서 SecureString 파라미터를 저장합니다.
 *
 * 이 작업은 AWS 측 상태를 변경하며 드러낸 값을 SecureString 평문으로 SSM에 전송합니다.
 * [overwrite]는 기존 값을 대체할 수 있는지 제어합니다. IAM/KMS 정책과 감사 경계는 호출자가 책임집니다.
 * 드러낸 값을 로그에 남기거나 출력하지 마세요.
 */
fun SsmAsyncClient.putSecureParameterAsync(
    name: String,
    value: AwsSecretValue,
    overwrite: Boolean = false,
    description: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): CompletableFuture<PutParameterResponse> =
    putParameter(putSecureParameterRequestOf(name, value, overwrite, description, overrideConfiguration))

/**
 * 비밀이 아닌 String 파라미터를 저장합니다.
 */
fun SsmAsyncClient.putStringParameterAsync(
    name: String,
    value: String,
    overwrite: Boolean = false,
    description: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): CompletableFuture<PutParameterResponse> =
    putParameter(putStringParameterRequestOf(name, value, overwrite, description, overrideConfiguration))

/**
 * 비밀이 아닌 StringList 파라미터를 저장합니다.
 */
fun SsmAsyncClient.putStringListParameterAsync(
    name: String,
    values: Collection<String>,
    overwrite: Boolean = false,
    description: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
): CompletableFuture<PutParameterResponse> =
    putParameter(putStringListParameterRequestOf(name, values, overwrite, description, overrideConfiguration))
