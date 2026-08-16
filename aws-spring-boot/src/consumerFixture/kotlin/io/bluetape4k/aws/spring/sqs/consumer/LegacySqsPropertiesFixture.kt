package io.bluetape4k.aws.spring.sqs.consumer

import io.bluetape4k.aws.spring.sqs.SqsProperties

/**
 * 배치 속성을 알지 못하는 기존 SQS 설정 consumer의 source 모양입니다.
 */
class LegacySqsPropertiesFixture {

    fun createProperties(): SqsProperties {
        val properties = SqsProperties(
            enabled = false,
            region = "us-east-1",
            listener = SqsProperties.Listener(concurrency = 2),
        )
        return properties.copy(enabled = true)
    }
}
