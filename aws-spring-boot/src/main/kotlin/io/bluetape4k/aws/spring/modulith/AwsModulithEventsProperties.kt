package io.bluetape4k.aws.spring.modulith

import io.bluetape4k.javatimes.days
import io.bluetape4k.javatimes.hours
import io.bluetape4k.javatimes.minutes
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.support.requireBeTrue
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requireMatches
import io.bluetape4k.support.requireNull
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.toUtf8Bytes
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.time.Duration

/**
 * Spring Modulith 이벤트 외부화와 inbound consumer의 선택적 설정입니다.
 *
 * 모든 기능은 기본적으로 꺼져 있으며, producer와 consumer를 각각 명시적으로
 * 활성화해야 합니다. AWS ARN이나 URL은 target destination으로 받지 않고 논리
 * 이름으로만 받습니다.
 */
@ConfigurationProperties(prefix = "bluetape4k.aws.modulith.events")
data class AwsModulithEventsProperties(
    var enabled: Boolean = false,
    var producer: Producer = Producer(),
    var consumer: Consumer = Consumer(),
    var targets: Map<String, Target> = emptyMap(),
): Serializable {
    init {
        targets = targets.toMap()
        validate()
    }

    /** 바인딩 이후의 교차 속성까지 포함해 설정을 다시 검증합니다. */
    internal fun validate() {
        targets = targets.toMap()
        targets.size.requireLe(MAX_TARGETS) {
            "bluetape4k.aws.modulith.events.targets must contain at most $MAX_TARGETS entries."
        }

        targets.forEach { (alias, target) ->
            requireLogicalName(alias, "target alias")
            target.validate()
        }
        producer.validate()
        if (producer.enabled) {
            if (targets.isEmpty()) {
                throw AwsModulithConfigurationException()
            }
        }
        consumer.validate()
    }

    /** SNS topic 또는 SQS queue로 보낼 논리적 destination입니다. */
    data class Target(
        val service: AwsModulithTargetService = AwsModulithTargetService.SNS,
        val destination: String = "",
    ): Serializable {
        init {
            validate()
        }

        internal fun validate() {
            destination.isAwsDestinationName().requireBeTrue {
                "destination must be a logical SNS topic or SQS queue name."
            }
            val maximumLength = when (service) {
                AwsModulithTargetService.SNS -> MAX_TOPIC_NAME_LENGTH
                AwsModulithTargetService.SQS -> MAX_QUEUE_NAME_LENGTH
            }
            destination.length.requireLe(maximumLength) {
                "destination must not exceed $maximumLength characters."
            }
        }

        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    /** outbound producer의 admission과 payload 크기 제한입니다. */
    data class Producer(
        var enabled: Boolean = false,
        var maxInFlight: Int = DEFAULT_MAX_IN_FLIGHT,
        var maxSerializedPayloadBytes: Int = DEFAULT_MAX_SERIALIZED_PAYLOAD_BYTES,
        var maxEnvelopeBytes: Int = DEFAULT_MAX_ENVELOPE_BYTES,
        var shutdownTimeout: Duration = DEFAULT_SHUTDOWN_TIMEOUT,
    ): Serializable {
        init {
            validate()
        }

        internal fun validate() {
            maxInFlight.requireInRange(MIN_MAX_IN_FLIGHT, MAX_MAX_IN_FLIGHT, "maxInFlight")
            maxSerializedPayloadBytes.requireInRange(MIN_BYTES, MAX_BYTES, "maxSerializedPayloadBytes")
            maxEnvelopeBytes.requireInRange(MIN_BYTES, MAX_BYTES, "maxEnvelopeBytes")
            shutdownTimeout.requireInRange(MIN_SHUTDOWN_TIMEOUT, MAX_SHUTDOWN_TIMEOUT, "shutdownTimeout")
        }

        companion object {
            private const val serialVersionUID: Long = 2L
        }
    }

    /** inbound source와 redrive 보호 설정입니다. */
    data class Consumer(
        var enabled: Boolean = false,
        var queue: String? = null,
        var sourceMode: AwsModulithSourceMode? = null,
        var expectedTopicArns: Set<String> = emptySet(),
        var redriveRequired: Boolean = true,
        var idempotency: Idempotency = Idempotency(),
    ): Serializable {
        init {
            expectedTopicArns = expectedTopicArns.toSet()
            validate()
        }

        internal fun validate() {
            expectedTopicArns = expectedTopicArns.toSet()
            idempotency.validate()
            queue?.let(::validateQueue)
            expectedTopicArns.forEach { requireSnsTopicArn(it) }
            validateSourceConstraints()
            validateEnablement()
        }

        private fun validateQueue(value: String) {
            value.isAwsDestinationName().requireBeTrue { "queue must be a logical SQS queue name." }
            value.length.requireLe(MAX_QUEUE_NAME_LENGTH) { "queue must not exceed $MAX_QUEUE_NAME_LENGTH characters." }
        }

        private fun validateSourceConstraints() {
            if (sourceMode == AwsModulithSourceMode.DIRECT && expectedTopicArns.isNotEmpty()) {
                throw AwsModulithConfigurationException()
            }
        }

        private fun validateEnablement() {
            if (!enabled) return
            if (queue.isNullOrBlank() || sourceMode == null) {
                throw AwsModulithConfigurationException()
            }
            if (sourceMode == AwsModulithSourceMode.SNS && expectedTopicArns.isEmpty()) {
                throw AwsModulithConfigurationException()
            }
        }

        companion object {
            private const val serialVersionUID: Long = 3L
        }
    }

    /** 기본 in-memory idempotency store의 bounded 용량과 lease 정책입니다. */
    data class Idempotency(
        var maxEntries: Int = DEFAULT_MAX_ENTRIES,
        var maxInProgress: Int = DEFAULT_MAX_IN_PROGRESS,
        var maxKeyBytes: Int = DEFAULT_MAX_KEY_BYTES,
        var retention: Duration = DEFAULT_RETENTION,
        var leaseDuration: Duration = DEFAULT_LEASE_DURATION,
    ): Serializable {
        init {
            validate()
        }

        internal fun validate() {
            maxEntries.requirePositiveNumber("maxEntries")
            maxInProgress.requireInRange(MIN_MAX_IN_PROGRESS, maxEntries, "maxInProgress")
            maxKeyBytes.requirePositiveNumber("maxKeyBytes")
            retention.requireInRange(MIN_RETENTION, MAX_RETENTION, "retention")
            leaseDuration.requireInRange(MIN_LEASE_DURATION, MAX_LEASE_DURATION, "leaseDuration")
        }

        companion object {
            private const val serialVersionUID: Long = 4L
        }
    }

    companion object {
        private const val serialVersionUID: Long = 5L
        private const val MAX_TARGETS = 100
        private const val MAX_TOPIC_NAME_LENGTH = 256
        private const val MAX_QUEUE_NAME_LENGTH = 80
        private const val DEFAULT_MAX_IN_FLIGHT = 64
        private const val MIN_MAX_IN_FLIGHT = 1
        private const val MAX_MAX_IN_FLIGHT = 1_024
        private const val DEFAULT_MAX_SERIALIZED_PAYLOAD_BYTES = 196_608
        private const val DEFAULT_MAX_ENVELOPE_BYTES = 262_144
        private const val MIN_BYTES = 1
        private const val MAX_BYTES = 262_144
        private const val DEFAULT_MAX_ENTRIES = 10_000
        private const val DEFAULT_MAX_IN_PROGRESS = 256
        private const val MIN_MAX_IN_PROGRESS = 1
        private const val DEFAULT_MAX_KEY_BYTES = 2_097_152
        private val DEFAULT_SHUTDOWN_TIMEOUT = 25.seconds()
        private val MIN_SHUTDOWN_TIMEOUT = 1.seconds()
        private val MAX_SHUTDOWN_TIMEOUT = 5.minutes()
        private val DEFAULT_RETENTION = 24.hours()
        private val MIN_RETENTION = 1.minutes()
        private val MAX_RETENTION = 7.days()
        private val DEFAULT_LEASE_DURATION = 2.minutes()
        private val MIN_LEASE_DURATION = 30.seconds()
        private val MAX_LEASE_DURATION = 30.minutes()
    }
}

/** 외부화 destination에 사용할 AWS service입니다. */
enum class AwsModulithTargetService {
    SNS,
    SQS,
}

/** inbound SQS body의 출처 검증 방식입니다. */
enum class AwsModulithSourceMode {
    DIRECT,
    SNS,
}

/** standard/FIFO destination의 routing key를 publication 경계에서 검증합니다. */
internal fun validateAwsModulithRoutingKey(destination: String, routingKey: String?): String? {
    val fifo = destination.endsWith(".fifo")
    if (!fifo) {
        routingKey.requireNull { "routingKey is not allowed for a standard destination." }
        return null
    }
    require(!routingKey.isNullOrBlank()) {
        "routingKey is required for a FIFO destination."
    }
    require(routingKey.none(Char::isISOControl)) {
        "routingKey must not contain control characters."
    }
    routingKey.toUtf8Bytes().size.requireLe(MAX_ROUTING_KEY_BYTES) {
        "routingKey must not exceed $MAX_ROUTING_KEY_BYTES UTF-8 bytes."
    }
    return routingKey
}

private fun requireLogicalName(value: String, field: String) {
    require(value.isLogicalAwsName()) {
        "$field must be a non-blank logical name."
    }
}

private fun String.isLogicalAwsName(): Boolean =
    matches(LOGICAL_ALIAS_PATTERN) &&
            !startsWith("arn:", ignoreCase = true) &&
            !startsWith("http://", ignoreCase = true) &&
            !startsWith("https://", ignoreCase = true) &&
            !contains("://")

private fun String.isAwsDestinationName(): Boolean =
    matches(SNS_QUEUE_NAME_PATTERN) &&
            !startsWith("arn:", ignoreCase = true) &&
            !startsWith("http://", ignoreCase = true) &&
            !startsWith("https://", ignoreCase = true) &&
            !contains("://")

private fun requireSnsTopicArn(value: String) {
    value.requireMatches(SNS_TOPIC_ARN_PATTERN, "value") {
        "expectedTopicArns must contain valid SNS topic ARNs."
    }
}

private val LOGICAL_ALIAS_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
private val SNS_QUEUE_NAME_PATTERN = Regex("[A-Za-z0-9_-]+(?:\\.fifo)?")
private val SNS_TOPIC_ARN_PATTERN = Regex(
    "arn:[a-z0-9-]+:sns:[a-z0-9-]+:[0-9]{12}:[A-Za-z0-9_-]+(?:\\.fifo)?"
)
private const val MAX_ROUTING_KEY_BYTES = 128
