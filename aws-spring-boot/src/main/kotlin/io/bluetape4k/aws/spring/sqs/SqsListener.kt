package io.bluetape4k.aws.spring.sqs

/**
 * SQS 큐 메시지를 수신할 메서드를 표시합니다.
 *
 * - [queue]는 큐 이름, 큐 URL, 또는 `${...}` 플레이스홀더입니다. SpEL은 지원하지 않습니다.
 * - [maxMessages], [waitTimeSeconds], [visibilityTimeoutSeconds],
 *   [errorVisibilityTimeoutSeconds]가 음수이면 전역 리스너 설정을 사용합니다.
 * - 리스너 메서드는 `String`, AWS SDK [software.amazon.awssdk.services.sqs.model.Message],
 *   [SqsReceivedMessage] 중 하나의 인자를 받을 수 있습니다.
 *
 * ```kotlin
 * import org.springframework.stereotype.Component
 *
 * @Component
 * class OrderListener {
 *
 *     @SqsListener(queue = "orders", maxMessages = 10, waitTimeSeconds = 20)
 *     suspend fun handle(message: SqsReceivedMessage) {
 *         check(message.body.isNotBlank())
 *     }
 * }
 * ```
 *
 * `queue = "orders"`는 `bluetape4k.aws.sqs.queues.orders.url` 설정이 있으면 해당 URL로
 * 해석되고, 설정이 없으면 SQS `GetQueueUrl` 요청으로 큐 URL을 조회합니다.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class SqsListener(
    val queue: String,
    val id: String = "",
    val maxMessages: Int = -1,
    val waitTimeSeconds: Int = -1,
    val visibilityTimeoutSeconds: Int = -1,
    val errorVisibilityTimeoutSeconds: Int = -1,
    val autoStartup: Boolean = true,
    val batch: Boolean = false,
    val acknowledgementMode: SqsAcknowledgementMode = SqsAcknowledgementMode.INHERIT,
)
