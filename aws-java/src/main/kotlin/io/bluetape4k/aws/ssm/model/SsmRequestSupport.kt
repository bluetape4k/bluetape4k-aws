package io.bluetape4k.aws.ssm.model

import io.bluetape4k.aws.secretsmanager.AwsSecretValue
import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.services.ssm.model.DescribeParametersRequest
import software.amazon.awssdk.services.ssm.model.GetParameterRequest
import software.amazon.awssdk.services.ssm.model.GetParametersByPathRequest
import software.amazon.awssdk.services.ssm.model.GetParametersRequest
import software.amazon.awssdk.services.ssm.model.ParameterType
import software.amazon.awssdk.services.ssm.model.PutParameterRequest

/**
 * [GetParameterRequest]를 구성합니다.
 */
inline fun getParameterRequestOf(
    name: String,
    withDecryption: Boolean = false,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: GetParameterRequest.Builder.() -> Unit = {},
): GetParameterRequest {
    name.requireNotBlank("name")

    return GetParameterRequest.builder()
        .name(name)
        .withDecryption(withDecryption)
        .apply {
            overrideConfiguration?.let { overrideConfiguration(it) }
            builder()
        }
        .build()
}

/**
 * 최대 10개의 이름으로 [GetParametersRequest]를 구성합니다.
 */
inline fun getParametersRequestOf(
    names: Collection<String>,
    withDecryption: Boolean = false,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: GetParametersRequest.Builder.() -> Unit = {},
): GetParametersRequest {
    require(names.isNotEmpty()) { "names must not be empty" }
    require(names.size <= 10) { "names size must be less than or equal to 10" }
    names.forEach { it.requireNotBlank("name") }

    return GetParametersRequest.builder()
        .names(names)
        .withDecryption(withDecryption)
        .apply {
            overrideConfiguration?.let { overrideConfiguration(it) }
            builder()
        }
        .build()
}

/**
 * 단일 페이지 [GetParametersByPathRequest]를 구성합니다.
 */
inline fun getParametersByPathRequestOf(
    path: String,
    recursive: Boolean? = null,
    withDecryption: Boolean = false,
    maxResults: Int? = null,
    nextToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: GetParametersByPathRequest.Builder.() -> Unit = {},
): GetParametersByPathRequest {
    path.requireNotBlank("path")
    nextToken?.requireNotBlank("nextToken")

    return GetParametersByPathRequest.builder()
        .path(path)
        .withDecryption(withDecryption)
        .apply {
            recursive?.let { recursive(it) }
            maxResults?.let { maxResults(it) }
            nextToken?.let { nextToken(it) }
            overrideConfiguration?.let { overrideConfiguration(it) }
            builder()
        }
        .build()
}

/**
 * 단일 페이지 [DescribeParametersRequest]를 구성합니다.
 */
inline fun describeParametersRequestOf(
    maxResults: Int? = null,
    nextToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: DescribeParametersRequest.Builder.() -> Unit = {},
): DescribeParametersRequest {
    nextToken?.requireNotBlank("nextToken")

    return DescribeParametersRequest.builder()
        .apply {
            maxResults?.let { maxResults(it) }
            nextToken?.let { nextToken(it) }
            overrideConfiguration?.let { overrideConfiguration(it) }
            builder()
        }
        .build()
}

/**
 * 값이 가려진 래퍼에서 SecureString [PutParameterRequest]를 구성합니다.
 *
 * 드러낸 값은 SSM으로 전송하기 위해 AWS SDK 요청에 평문으로 담깁니다. [overwrite]는 AWS가 기존
 * 파라미터 값을 대체할 수 있는지 제어하며 IAM/KMS 정책과 감사 경계는 호출자가 책임집니다.
 */
inline fun putSecureParameterRequestOf(
    name: String,
    value: AwsSecretValue,
    overwrite: Boolean = false,
    description: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: PutParameterRequest.Builder.() -> Unit = {},
): PutParameterRequest =
    putParameterRequestOf(name, value.reveal(), ParameterType.SECURE_STRING, overwrite, description, overrideConfiguration, builder)

/**
 * 비밀이 아닌 String [PutParameterRequest]를 구성합니다.
 */
inline fun putStringParameterRequestOf(
    name: String,
    value: String,
    overwrite: Boolean = false,
    description: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: PutParameterRequest.Builder.() -> Unit = {},
): PutParameterRequest =
    putParameterRequestOf(name, value, ParameterType.STRING, overwrite, description, overrideConfiguration, builder)

/**
 * 비밀이 아닌 StringList [PutParameterRequest]를 구성합니다.
 */
inline fun putStringListParameterRequestOf(
    name: String,
    values: Collection<String>,
    overwrite: Boolean = false,
    description: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: PutParameterRequest.Builder.() -> Unit = {},
): PutParameterRequest {
    require(values.isNotEmpty()) { "values must not be empty" }
    values.forEach { it.requireNotBlank("value") }
    return putParameterRequestOf(name, values.joinToString(","), ParameterType.STRING_LIST, overwrite, description, overrideConfiguration, builder)
}

/**
 * 타입이 지정된 [PutParameterRequest]를 구성합니다.
 *
 * 보안 값이 SDK 요청 경계까지 래핑된 상태를 유지하도록 `SecureString` 쓰기에는
 * [putSecureParameterRequestOf]를 사용하세요.
 */
inline fun putParameterRequestOf(
    name: String,
    value: String,
    type: ParameterType,
    overwrite: Boolean = false,
    description: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: PutParameterRequest.Builder.() -> Unit = {},
): PutParameterRequest {
    name.requireNotBlank("name")
    value.requireNotBlank("value")
    description?.requireNotBlank("description")

    return PutParameterRequest.builder()
        .name(name)
        .value(value)
        .type(type)
        .overwrite(overwrite)
        .apply {
            description?.let { description(it) }
            overrideConfiguration?.let { overrideConfiguration(it) }
            builder()
        }
        .build()
}
