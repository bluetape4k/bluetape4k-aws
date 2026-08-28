package io.bluetape4k.aws.spring.sqs

import io.micrometer.common.KeyValue
import io.micrometer.common.KeyValues
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationConvention

const val SQS_RECEIVE_OBSERVATION_NAME: String = "bluetape4k.aws.sqs.receive"
const val SQS_PROCESS_OBSERVATION_NAME: String = "bluetape4k.aws.sqs.process"
const val SQS_ACKNOWLEDGEMENT_OBSERVATION_NAME: String = "bluetape4k.aws.sqs.acknowledgement"

/**
 * SQS observation의 이름과 tag를 특정 [stage]에 제공하는 확장 계약입니다.
 *
 * 같은 stage의 사용자 convention은 하나만 등록할 수 있습니다. 기본 구현은 정제된
 * [SqsObservationContext]만 읽으며 메시지 본문, receipt handle 또는 전체 queue URL에
 * 접근하지 않습니다.
 */
interface SqsObservationConvention : ObservationConvention<SqsObservationContext> {
    val stage: SqsObservationStage

    override fun supportsContext(context: Observation.Context): Boolean =
        context is SqsObservationContext && context.metadata.stage == stage
}

internal fun defaultSqsObservationConventions(): Map<SqsObservationStage, SqsObservationConvention> =
    SqsObservationStage.entries.associateWith(::DefaultSqsObservationConvention)

internal fun resolveSqsObservationConventions(
    userConventions: List<SqsObservationConvention>,
): Map<SqsObservationStage, SqsObservationConvention> {
    val conventions = defaultSqsObservationConventions().toMutableMap()
    userConventions.groupBy(SqsObservationConvention::stage).forEach { (stage, candidates) ->
        check(candidates.size == 1) {
            "Only one SqsObservationConvention may be registered for stage $stage."
        }
        conventions[stage] = candidates.single()
    }
    return conventions.toMap()
}

private class DefaultSqsObservationConvention(
    override val stage: SqsObservationStage,
) : SqsObservationConvention {

    override fun getName(): String = when (stage) {
        SqsObservationStage.RECEIVE -> SQS_RECEIVE_OBSERVATION_NAME
        SqsObservationStage.PROCESS -> SQS_PROCESS_OBSERVATION_NAME
        SqsObservationStage.ACKNOWLEDGEMENT -> SQS_ACKNOWLEDGEMENT_OBSERVATION_NAME
    }

    override fun getContextualName(context: SqsObservationContext): String =
        when (stage) {
            SqsObservationStage.RECEIVE -> "${context.metadata.queueName} receive"
            SqsObservationStage.PROCESS -> "${context.metadata.queueName} process"
            SqsObservationStage.ACKNOWLEDGEMENT ->
                "${context.metadata.queueName} ${context.metadata.acknowledgementAction.toTagValue()}"
        }

    override fun getLowCardinalityKeyValues(context: SqsObservationContext): KeyValues = KeyValues.of(
        "messaging.system", "sqs",
        "messaging.operation", stage.name.lowercase(),
        "messaging.destination.name", context.metadata.queueName,
        "bluetape4k.aws.sqs.listener.id", context.metadata.listenerId,
        "bluetape4k.aws.sqs.outcome", context.outcome.name.lowercase(),
        "bluetape4k.aws.sqs.ack.action", context.metadata.acknowledgementAction.toTagValue(),
        "bluetape4k.aws.sqs.batch.size", context.metadata.batchSize.toBatchSizeBucket(),
        "bluetape4k.aws.sqs.delivery", context.deliveryTagValue(),
        "bluetape4k.aws.sqs.failure.stage", context.failureStage.toFailureStageTag(),
    )

    override fun getHighCardinalityKeyValues(context: SqsObservationContext): KeyValues {
        if (context.metadata.batch || stage == SqsObservationStage.RECEIVE) {
            return KeyValues.empty()
        }
        val keyValues = buildList {
            context.metadata.messageId?.let { add(KeyValue.of("messaging.message.id", it)) }
            context.metadata.messageGroupId?.let { add(KeyValue.of("messaging.sqs.message.group.id", it)) }
            context.metadata.messageDeduplicationId?.let {
                add(KeyValue.of("messaging.sqs.message.deduplication.id", it))
            }
            context.attempt?.let { add(KeyValue.of("bluetape4k.aws.sqs.attempt", it.toString())) }
        }
        return KeyValues.of(keyValues)
    }
}

private fun SqsAcknowledgementAction?.toTagValue(): String = when (this) {
    null -> "none"
    SqsAcknowledgementAction.ACK -> "ack"
    SqsAcknowledgementAction.NACK -> "nack"
    SqsAcknowledgementAction.CHANGE_VISIBILITY -> "change_visibility"
}

private fun Int.toBatchSizeBucket(): String = when (this) {
    0 -> "0"
    1 -> "1"
    in MIN_GROUPED_BATCH_SIZE..MAX_SMALL_BATCH_SIZE -> "2-5"
    else -> "6-10"
}

private fun String?.toFailureStageTag(): String = when (this) {
    null -> "none"
    "receive", "conversion", "handler", "acknowledgement", "observation" -> this
    else -> "observation"
}

private fun SqsObservationContext.deliveryTagValue(): String =
    if (metadata.batch || metadata.stage == SqsObservationStage.RECEIVE) {
        "unknown"
    } else {
        metadata.delivery.name.lowercase()
    }

private const val MIN_GROUPED_BATCH_SIZE: Int = 2
private const val MAX_SMALL_BATCH_SIZE: Int = 5
