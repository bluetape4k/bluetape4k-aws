package io.bluetape4k.aws.spring.modulith.consumer

import io.bluetape4k.aws.spring.modulith.AwsModulithAcknowledgementException
import io.bluetape4k.aws.spring.modulith.AwsModulithClaimCapacityException
import io.bluetape4k.aws.spring.modulith.AwsModulithClaimMutationException
import io.bluetape4k.aws.spring.modulith.AwsModulithClaimResult
import io.bluetape4k.aws.spring.modulith.AwsModulithClaimToken
import io.bluetape4k.aws.spring.modulith.AwsModulithConfigurationException
import io.bluetape4k.aws.spring.modulith.AwsModulithConsumeOutcome
import io.bluetape4k.aws.spring.modulith.AwsModulithDispatchException
import io.bluetape4k.aws.spring.modulith.AwsModulithEventTypeRegistration
import io.bluetape4k.aws.spring.modulith.AwsModulithEventTypeRegistry
import io.bluetape4k.aws.spring.modulith.AwsModulithEventException
import io.bluetape4k.aws.spring.modulith.AwsModulithEventIdempotencyStore
import io.bluetape4k.aws.spring.modulith.AwsModulithEventInProgressException
import io.bluetape4k.aws.spring.modulith.AwsModulithEventKey
import io.bluetape4k.aws.spring.modulith.AwsModulithEventRegistrationMismatchException
import io.bluetape4k.aws.spring.modulith.AwsModulithInboundEnvelopeException
import io.bluetape4k.aws.spring.modulith.AwsModulithInboundLoopRiskException
import io.bluetape4k.aws.spring.modulith.AwsModulithOutboundEnvelopeException
import io.bluetape4k.aws.spring.modulith.AwsModulithProducerCapacityException
import io.bluetape4k.aws.spring.modulith.AwsModulithProducerClosedException
import io.bluetape4k.aws.spring.modulith.AwsModulithPublishException
import io.bluetape4k.aws.spring.modulith.AwsModulithSqsEventConsumer
import io.bluetape4k.aws.spring.modulith.AwsModulithStaleClaimException
import io.bluetape4k.aws.spring.modulith.AwsModulithStoreMutation
import io.bluetape4k.aws.spring.modulith.AwsModulithSourceException
import io.bluetape4k.aws.spring.modulith.AwsModulithTargetResolutionException
import io.bluetape4k.aws.spring.modulith.AwsModulithUnknownEventTypeException
import io.bluetape4k.aws.spring.modulith.AwsModulithUnsupportedEventVersionException
import java.time.Duration
import java.time.Instant

/** Spring Modulith public registration API를 사용하는 외부 consumer fixture입니다. */
class FixtureEvent(val id: String)

val fixtureRegistry = AwsModulithEventTypeRegistry.of(
    AwsModulithEventTypeRegistration(
        type = "fixture.event",
        version = 1,
        eventClass = FixtureEvent::class.java,
        eventId = FixtureEvent::id,
    ),
)

/** Spring이 internal constructor를 호출하는 외부 consumer injection 계약입니다. */
data class ConsumerInjection(val consumer: AwsModulithSqsEventConsumer)

/** 외부 애플리케이션이 구현할 수 있는 최소 idempotency store 계약입니다. */
class FixtureStore : AwsModulithEventIdempotencyStore {
    override suspend fun claim(key: AwsModulithEventKey, leaseDuration: Duration): AwsModulithClaimResult =
        AwsModulithClaimResult.Acquired(
            AwsModulithClaimToken(
                key = key,
                ownerId = "fixture",
                generation = 1L,
                leaseUntil = Instant.EPOCH.plus(leaseDuration),
            ),
        )

    override suspend fun renew(
        token: AwsModulithClaimToken,
        leaseDuration: Duration,
    ): AwsModulithClaimToken = token.copy(leaseUntil = Instant.EPOCH.plus(leaseDuration))

    override suspend fun complete(token: AwsModulithClaimToken): AwsModulithStoreMutation =
        AwsModulithStoreMutation.APPLIED

    override suspend fun release(token: AwsModulithClaimToken): AwsModulithStoreMutation =
        AwsModulithStoreMutation.APPLIED

    override suspend fun recoverExpired(now: Instant): Int = 0
}

val fixtureConsumeOutcome = AwsModulithConsumeOutcome.PROCESSED

/** public exception ABI의 field와 모든 public subtype 참조를 고정하는 외부 fixture입니다. */
fun describeFixtureException(exception: AwsModulithEventException): List<Any?> {
    val fields = listOf(
        exception.code,
        exception.phase,
        exception.retryable,
        exception.callerAction,
        exception.message,
        exception.cause,
    )
    val kind = when (exception) {
        is AwsModulithConfigurationException -> "configuration"
        is AwsModulithEventRegistrationMismatchException -> "registration-mismatch"
        is AwsModulithOutboundEnvelopeException -> "outbound-envelope"
        is AwsModulithProducerCapacityException -> "producer-capacity"
        is AwsModulithProducerClosedException -> "producer-closed"
        is AwsModulithTargetResolutionException -> "target-resolution"
        is AwsModulithPublishException -> "publish"
        is AwsModulithSourceException -> "source"
        is AwsModulithInboundEnvelopeException -> "inbound-envelope"
        is AwsModulithUnknownEventTypeException -> "unknown-event-type"
        is AwsModulithUnsupportedEventVersionException -> "unsupported-event-version"
        is AwsModulithInboundLoopRiskException -> "inbound-loop-risk"
        is AwsModulithClaimCapacityException -> "claim-capacity"
        is AwsModulithEventInProgressException -> "event-in-progress"
        is AwsModulithStaleClaimException -> "stale-claim"
        is AwsModulithClaimMutationException -> "claim-mutation"
        is AwsModulithDispatchException -> "dispatch"
        is AwsModulithAcknowledgementException -> "acknowledgement"
        else -> "unknown"
    }
    return fields + kind
}
