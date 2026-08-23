package io.bluetape4k.aws.spring.config

import org.springframework.boot.context.config.ConfigData
import org.springframework.boot.context.config.ConfigDataResourceNotFoundException
import org.springframework.core.env.MapPropertySource
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException

/** 기존 EPP와 ConfigData의 오류 처리 정책을 구분합니다. */
internal object AwsConfigDataFailurePolicy {

    @Suppress("TooGenericExceptionCaught")
    fun load(
        resource: AwsConfigDataResource,
        fetch: () -> Map<String, Any>,
    ): Map<String, Any>? = try {
        fetch()
    } catch (error: RuntimeException) {
        if (isNotFound(resource.location.backend, error)) {
            if (resource.isOptionalResource) {
                null
            } else {
                throw ConfigDataResourceNotFoundException(resource)
            }
        } else {
            throw AwsConfigDataLoadException(resource.backendKey, error::class.java.simpleName)
        }
    }

    fun toConfigData(resource: AwsConfigDataResource, values: Map<String, Any>): ConfigData =
        ConfigData(listOf(MapPropertySource(resource.toString(), values)))

    private fun isNotFound(backend: AwsConfigDataBackend, error: RuntimeException): Boolean = when (backend) {
        AwsConfigDataBackend.S3 -> when (error) {
            is NoSuchBucketException, is NoSuchKeyException -> true
            is S3Exception -> error.statusCode() == NOT_FOUND_STATUS_CODE
            else -> false
        }

        AwsConfigDataBackend.PARAMETER_STORE -> error is ParameterNotFoundException
        AwsConfigDataBackend.SECRETS_MANAGER -> error is ResourceNotFoundException
        AwsConfigDataBackend.APP_CONFIG -> error::class.java.name == APP_CONFIG_NOT_FOUND_EXCEPTION
    }

    private const val NOT_FOUND_STATUS_CODE = 404
    private const val APP_CONFIG_NOT_FOUND_EXCEPTION =
        "software.amazon.awssdk.services.appconfigdata.model.ResourceNotFoundException"
}

/** raw SDK message와 cause를 보관하지 않는 ConfigData 시작 실패입니다. */
internal class AwsConfigDataLoadException(
    backend: String,
    errorType: String,
) : RuntimeException("AWS ConfigData $backend load failed ($errorType).")
