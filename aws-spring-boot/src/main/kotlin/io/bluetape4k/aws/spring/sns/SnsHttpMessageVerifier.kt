package io.bluetape4k.aws.spring.sns

import software.amazon.awssdk.messagemanager.sns.SnsMessageManager
import software.amazon.awssdk.regions.Region
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SNS HTTP 메시지의 구조와 AWS 서명을 순서대로 검증하는 공통 경계입니다.
 *
 * parser 결과만 반환하며 HTTP adapter lifecycle이나 업무 handler 호출은 담당하지 않습니다.
 */
class SnsHttpMessageVerifier(
    private val messageManager: SnsMessageManager = SnsMessageManager.builder().build(),
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    /**
     * 원문 SNS JSON을 parser로 구조 검증한 뒤 AWS SDK manager로 서명을 검증합니다.
     *
     * @throws IllegalArgumentException parser 또는 expected TopicArn 검증에 실패한 경우
     * @throws RuntimeException SDK manager가 서명·인증서 검증에 실패한 경우
     */
    fun verify(
        json: String,
        messageTypeHeader: String? = null,
        expectedTopicArn: String? = null,
    ): SnsHttpMessage {
        val parsed = SnsHttpMessageParser.parse(json, messageTypeHeader)
        expectedTopicArn?.let { expected ->
            require(expected.isNotBlank()) { "expectedTopicArn must not be blank." }
            require(parsed.topicArn == expected) {
                "SNS HTTP message TopicArn does not match expectedTopicArn."
            }
        }

        messageManager.parseMessage(json)
        return parsed
    }

    /**
     * SDK manager를 한 번만 닫습니다.
     */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            messageManager.close()
        }
    }

    companion object {
        /**
         * 지정한 AWS region을 사용하는 verifier를 생성합니다.
         */
        fun forRegion(region: String?): SnsHttpMessageVerifier {
            val builder = SnsMessageManager.builder()
            region?.let {
                require(it.isNotBlank()) { "region must not be blank." }
                builder.region(Region.of(it))
            }
            return SnsHttpMessageVerifier(builder.build())
        }
    }
}
