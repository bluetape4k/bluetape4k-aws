package io.bluetape4k.aws.spring.modulith

import io.bluetape4k.aws.spring.sns.SnsOperations
import io.bluetape4k.aws.spring.sns.SnsPublishRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import java.security.MessageDigest

/** 기존 [SnsOperations]에 Modulith envelope를 연결하는 내부 SNS publisher입니다. */
internal class AwsModulithSnsTargetPublisher(
    private val snsOperations: SnsOperations,
) : AwsModulithTargetPublisher {

    override suspend fun publish(command: AwsModulithPublishCommand): AwsModulithPublishResult {
        currentCoroutineContext().ensureActive()
        val destination = requireAwsModulithDestinationName(command.destination)
        val routingKey = validateAwsModulithRoutingKey(destination, command.routingKey)
        val topicArn = sanitizeAwsModulithResolutionCall { snsOperations.findTopicArn(destination) }
            ?: throw AwsModulithTargetResolutionException()
        currentCoroutineContext().ensureActive()
        val response = sanitizeAwsModulithPublishCall {
            snsOperations.publish(
                SnsPublishRequest(
                    topicArn = topicArn,
                    message = command.encoded.body,
                    messageAttributes = command.encoded.messageAttributes.toAwsMessageAttributes(),
                    messageGroupId = routingKey,
                    messageDeduplicationId = routingKey?.let { sha256(command.eventId) },
                ),
            )
        }
        return AwsModulithPublishResult(
            service = AwsModulithTargetService.SNS,
            targetAlias = command.targetAlias,
            providerMessageIdPresent = response.messageId() != null,
        )
    }
}

private fun Map<String, String>.toAwsMessageAttributes(): Map<String, MessageAttributeValue> =
    mapValues { (_, value) ->
        MessageAttributeValue.builder()
            .dataType("String")
            .stringValue(value)
            .build()
    }

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { "%02x".format(it) }
