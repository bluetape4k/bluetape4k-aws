package io.bluetape4k.aws.kotlin.sqs.model

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

class ReceiveMessageTest {

    companion object : KLogging()

    private val queueUrl = "https://sqs.ap-northeast-2.amazonaws.com/123456789012/MyQueue"

    @Test
    fun `receiveMessageRequestOf는 queueUrl로 요청을 생성한다`() {
        val req = receiveMessageRequestOf(queueUrl = queueUrl)

        req.queueUrl shouldBeEqualTo queueUrl
        req.maxNumberOfMessages shouldBeEqualTo 3
        req.waitTimeSeconds shouldBeEqualTo 20
    }

    @Test
    fun `receiveMessageRequestOf는 maxNumberOfMessages를 설정할 수 있다`() {
        val req = receiveMessageRequestOf(queueUrl = queueUrl, maxNumberOfMessages = 5)

        req.maxNumberOfMessages shouldBeEqualTo 5
    }

    @Test
    fun `receiveMessageRequestOf는 waitTimeSeconds를 설정할 수 있다`() {
        val req = receiveMessageRequestOf(queueUrl = queueUrl, waitTimeSeconds = 10)

        req.waitTimeSeconds shouldBeEqualTo 10
    }

    @Test
    fun `receiveMessageRequestOf는 수신 개수와 대기 시간 경계값을 허용한다`() {
        val minReq = receiveMessageRequestOf(queueUrl = queueUrl, maxNumberOfMessages = 1, waitTimeSeconds = 0)
        val maxReq = receiveMessageRequestOf(queueUrl = queueUrl, maxNumberOfMessages = 10, waitTimeSeconds = 20)

        minReq.maxNumberOfMessages shouldBeEqualTo 1
        minReq.waitTimeSeconds shouldBeEqualTo 0
        maxReq.maxNumberOfMessages shouldBeEqualTo 10
        maxReq.waitTimeSeconds shouldBeEqualTo 20
    }

    @Test
    fun `receiveMessageRequestOf는 visibilityTimeout을 설정할 수 있다`() {
        val req = receiveMessageRequestOf(queueUrl = queueUrl, visibilityTimeout = 30)

        req.visibilityTimeout shouldBeEqualTo 30
    }

    @Test
    fun `receiveMessageRequestOf는 attributeNames를 설정할 수 있다`() {
        val req = receiveMessageRequestOf(
            queueUrl = queueUrl,
            attributeNames = listOf("All")
        )

        req.messageAttributeNames.shouldNotBeNull()
        req.messageAttributeNames.shouldNotBeNull() shouldBeEqualTo listOf("All")
    }

    @Test
    fun `receiveMessageRequestOf는 builder 블록으로 FIFO 수신 토큰을 설정할 수 있다`() {
        val req = receiveMessageRequestOf(queueUrl = queueUrl) {
            receiveRequestAttemptId = "attempt-001"
        }

        req.receiveRequestAttemptId shouldBeEqualTo "attempt-001"
    }

    @Test
    fun `receiveMessageRequestOf는 빈 queueUrl을 허용하지 않는다`() {
        assertFailsWith<IllegalArgumentException> {
            receiveMessageRequestOf(queueUrl = "")
        }
    }

    @Test
    fun `receiveMessageRequestOf는 maxNumberOfMessages 범위를 검증한다`() {
        assertFailsWith<IllegalArgumentException> {
            receiveMessageRequestOf(queueUrl = queueUrl, maxNumberOfMessages = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            receiveMessageRequestOf(queueUrl = queueUrl, maxNumberOfMessages = 11)
        }
    }

    @Test
    fun `receiveMessageRequestOf는 waitTimeSeconds 범위를 검증한다`() {
        assertFailsWith<IllegalArgumentException> {
            receiveMessageRequestOf(queueUrl = queueUrl, waitTimeSeconds = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            receiveMessageRequestOf(queueUrl = queueUrl, waitTimeSeconds = 21)
        }
    }
}
