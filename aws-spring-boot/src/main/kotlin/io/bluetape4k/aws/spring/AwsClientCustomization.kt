package io.bluetape4k.aws.spring

import software.amazon.awssdk.awscore.client.builder.AwsAsyncClientBuilder
import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder
import java.io.Serializable

/**
 * 전역 AWS SDK v2 클라이언트 사용자 정의 설정에 전달하는 컨텍스트입니다.
 */
data class AwsClientCustomizationContext(
    val serviceName: String,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = -6912330727448422635L
    }
}

/**
 * 이 모듈이 생성하는 모든 동기 AWS SDK v2 클라이언트 빌더를 사용자 정의합니다.
 */
fun interface AwsSyncClientCustomizer {
    fun customize(
        context: AwsClientCustomizationContext,
        builder: AwsSyncClientBuilder<*, *>,
    )
}

/**
 * 이 모듈이 생성하는 모든 비동기 AWS SDK v2 클라이언트 빌더를 사용자 정의합니다.
 */
fun interface AwsAsyncClientCustomizer {
    fun customize(
        context: AwsClientCustomizationContext,
        builder: AwsAsyncClientBuilder<*, *>,
    )
}

/**
 * 특정 AWS SDK v2 빌더 타입을 사용자 정의합니다.
 */
fun interface AwsClientCustomizer<B> {
    fun customize(builder: B)
}
