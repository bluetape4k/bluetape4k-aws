package io.bluetape4k.aws.kotlin.ssm

import aws.sdk.kotlin.services.ssm.SsmClient
import aws.sdk.kotlin.services.ssm.describeParameters
import aws.sdk.kotlin.services.ssm.getParameter
import aws.sdk.kotlin.services.ssm.getParameters
import aws.sdk.kotlin.services.ssm.getParametersByPath
import aws.sdk.kotlin.services.ssm.model.DescribeParametersResponse
import aws.sdk.kotlin.services.ssm.model.GetParameterResponse
import aws.sdk.kotlin.services.ssm.model.GetParametersByPathResponse
import aws.sdk.kotlin.services.ssm.model.GetParametersResponse
import aws.sdk.kotlin.services.ssm.model.PutParameterResponse
import aws.sdk.kotlin.services.ssm.putParameter
import io.bluetape4k.aws.kotlin.secretsmanager.AwsSecretValue
import io.bluetape4k.aws.kotlin.secretsmanager.awsSecretValueOf
import io.bluetape4k.aws.kotlin.ssm.model.describeParametersRequestOf
import io.bluetape4k.aws.kotlin.ssm.model.getParameterRequestOf
import io.bluetape4k.aws.kotlin.ssm.model.getParametersByPathRequestOf
import io.bluetape4k.aws.kotlin.ssm.model.getParametersRequestOf
import io.bluetape4k.aws.kotlin.ssm.model.putSecureParameterRequestOf
import io.bluetape4k.aws.kotlin.ssm.model.putStringListParameterRequestOf
import io.bluetape4k.aws.kotlin.ssm.model.putStringParameterRequestOf

/**
 * 기본적으로 복호화 없이 파라미터를 가져옵니다.
 */
suspend fun SsmClient.getParameter(
    name: String,
    withDecryption: Boolean = false,
): GetParameterResponse =
    getParameter(getParameterRequestOf(name, withDecryption))

/**
 * 복호화를 활성화해 SecureString 파라미터를 가져오고 값을 가립니다.
 */
suspend fun SsmClient.getSecureParameter(name: String): AwsSecretValue =
    awsSecretValueOf(getParameter(name, withDecryption = true).parameter?.value ?: error("Parameter value is not present."))

/**
 * 최대 10개의 파라미터를 가져오고 SDK 원본의 잘못된 파라미터 상세 정보를 보존합니다.
 */
suspend fun SsmClient.getParameters(
    names: Collection<String>,
    withDecryption: Boolean = false,
): GetParametersResponse =
    getParameters(getParametersRequestOf(names, withDecryption))

/**
 * 경로에 해당하는 파라미터 페이지 하나를 가져옵니다.
 */
suspend fun SsmClient.getParametersByPath(
    path: String,
    recursive: Boolean? = null,
    withDecryption: Boolean = false,
    maxResults: Int? = null,
    nextToken: String? = null,
): GetParametersByPathResponse =
    getParametersByPath(getParametersByPathRequestOf(path, recursive, withDecryption, maxResults, nextToken))

/**
 * 파라미터 페이지 하나를 설명합니다.
 */
suspend fun SsmClient.describeParameters(
    maxResults: Int? = null,
    nextToken: String? = null,
): DescribeParametersResponse =
    describeParameters(describeParametersRequestOf(maxResults, nextToken))

/**
 * 값이 가려진 래퍼에서 SecureString 파라미터를 저장합니다.
 *
 * 이 작업은 AWS 측 상태를 변경하며 드러낸 값을 SecureString 평문으로 SSM에 전송합니다.
 * [overwrite]는 기존 값을 대체할 수 있는지 제어합니다. IAM/KMS 정책과 감사 경계는 호출자가 책임집니다.
 * 드러낸 값을 로그에 남기거나 출력하지 마세요.
 */
suspend fun SsmClient.putSecureParameter(
    name: String,
    value: AwsSecretValue,
    overwrite: Boolean = false,
    description: String? = null,
): PutParameterResponse =
    putParameter(putSecureParameterRequestOf(name, value, overwrite, description))

/**
 * 비밀이 아닌 String 파라미터를 저장합니다.
 */
suspend fun SsmClient.putStringParameter(
    name: String,
    value: String,
    overwrite: Boolean = false,
    description: String? = null,
): PutParameterResponse =
    putParameter(putStringParameterRequestOf(name, value, overwrite, description))

/**
 * 비밀이 아닌 StringList 파라미터를 저장합니다.
 */
suspend fun SsmClient.putStringListParameter(
    name: String,
    values: Collection<String>,
    overwrite: Boolean = false,
    description: String? = null,
): PutParameterResponse =
    putParameter(putStringListParameterRequestOf(name, values, overwrite, description))
