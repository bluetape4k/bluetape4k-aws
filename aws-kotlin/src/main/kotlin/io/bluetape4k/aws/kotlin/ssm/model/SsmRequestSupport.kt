package io.bluetape4k.aws.kotlin.ssm.model

import aws.sdk.kotlin.services.ssm.model.DescribeParametersRequest
import aws.sdk.kotlin.services.ssm.model.GetParameterRequest
import aws.sdk.kotlin.services.ssm.model.GetParametersByPathRequest
import aws.sdk.kotlin.services.ssm.model.GetParametersRequest
import aws.sdk.kotlin.services.ssm.model.ParameterType
import aws.sdk.kotlin.services.ssm.model.PutParameterRequest
import io.bluetape4k.aws.kotlin.secretsmanager.AwsSecretValue
import io.bluetape4k.support.requireNotBlank

/**
 * [GetParameterRequest]를 구성합니다.
 */
inline fun getParameterRequestOf(
    name: String,
    withDecryption: Boolean = false,
    crossinline builder: GetParameterRequest.Builder.() -> Unit = {},
): GetParameterRequest {
    name.requireNotBlank("name")

    return GetParameterRequest {
        this.name = name
        this.withDecryption = withDecryption
        builder()
    }
}

/**
 * 최대 10개의 이름으로 [GetParametersRequest]를 구성합니다.
 */
inline fun getParametersRequestOf(
    names: Collection<String>,
    withDecryption: Boolean = false,
    crossinline builder: GetParametersRequest.Builder.() -> Unit = {},
): GetParametersRequest {
    require(names.isNotEmpty()) { "names must not be empty" }
    require(names.size <= 10) { "names size must be less than or equal to 10" }
    names.forEach { it.requireNotBlank("name") }

    return GetParametersRequest {
        this.names = names.toList()
        this.withDecryption = withDecryption
        builder()
    }
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
    crossinline builder: GetParametersByPathRequest.Builder.() -> Unit = {},
): GetParametersByPathRequest {
    path.requireNotBlank("path")
    nextToken?.requireNotBlank("nextToken")

    return GetParametersByPathRequest {
        this.path = path
        this.recursive = recursive
        this.withDecryption = withDecryption
        this.maxResults = maxResults
        this.nextToken = nextToken
        builder()
    }
}

/**
 * 단일 페이지 [DescribeParametersRequest]를 구성합니다.
 */
inline fun describeParametersRequestOf(
    maxResults: Int? = null,
    nextToken: String? = null,
    crossinline builder: DescribeParametersRequest.Builder.() -> Unit = {},
): DescribeParametersRequest {
    nextToken?.requireNotBlank("nextToken")

    return DescribeParametersRequest {
        this.maxResults = maxResults
        this.nextToken = nextToken
        builder()
    }
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
    crossinline builder: PutParameterRequest.Builder.() -> Unit = {},
): PutParameterRequest =
    putParameterRequestOf(name, value.reveal(), ParameterType.SecureString, overwrite, description, builder)

/**
 * 비밀이 아닌 String [PutParameterRequest]를 구성합니다.
 */
inline fun putStringParameterRequestOf(
    name: String,
    value: String,
    overwrite: Boolean = false,
    description: String? = null,
    crossinline builder: PutParameterRequest.Builder.() -> Unit = {},
): PutParameterRequest =
    putParameterRequestOf(name, value, ParameterType.String, overwrite, description, builder)

/**
 * 비밀이 아닌 StringList [PutParameterRequest]를 구성합니다.
 */
inline fun putStringListParameterRequestOf(
    name: String,
    values: Collection<String>,
    overwrite: Boolean = false,
    description: String? = null,
    crossinline builder: PutParameterRequest.Builder.() -> Unit = {},
): PutParameterRequest {
    require(values.isNotEmpty()) { "values must not be empty" }
    values.forEach { it.requireNotBlank("value") }
    return putParameterRequestOf(name, values.joinToString(","), ParameterType.StringList, overwrite, description, builder)
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
    crossinline builder: PutParameterRequest.Builder.() -> Unit = {},
): PutParameterRequest {
    name.requireNotBlank("name")
    value.requireNotBlank("value")
    description?.requireNotBlank("description")

    return PutParameterRequest {
        this.name = name
        this.value = value
        this.type = type
        this.overwrite = overwrite
        this.description = description
        builder()
    }
}
