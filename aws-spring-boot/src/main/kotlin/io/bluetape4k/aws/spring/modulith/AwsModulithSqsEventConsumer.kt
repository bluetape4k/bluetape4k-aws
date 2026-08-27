package io.bluetape4k.aws.spring.modulith

import io.bluetape4k.aws.spring.sns.SnsHttpMessage
import io.bluetape4k.aws.spring.sns.SnsHttpMessageVerifier
import io.bluetape4k.aws.spring.sqs.SqsReceivedMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.springframework.context.ApplicationEventPublisher
import org.springframework.modulith.events.EventExternalizationConfiguration
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/** Public consumer가 정상적으로 완료할 수 있는 두 outcome입니다. */
enum class AwsModulithConsumeOutcome {
    PROCESSED,
    COMPLETED_DUPLICATE,
}

/** Source trust와 envelope decode가 끝난 immutable inbound 값입니다. */
internal data class AwsModulithDecodedInboundEvent(
    val event: Any,
    val key: AwsModulithEventKey,
)

/** SQS source를 검증하고 local event로 복원하는 내부 경계입니다. */
internal fun interface AwsModulithInboundSourceDecoder {
    fun decode(message: SqsReceivedMessage): AwsModulithDecodedInboundEvent
}

/** Test verifier와 production signature verifier를 분리하는 내부 계약입니다. */
internal fun interface AwsModulithSnsNotificationVerifier {
    fun verify(json: String, expectedTopicArn: String): SnsHttpMessage
}

internal class DefaultAwsModulithSnsNotificationVerifier(
    private val delegate: SnsHttpMessageVerifier,
) : AwsModulithSnsNotificationVerifier {
    override fun verify(json: String, expectedTopicArn: String): SnsHttpMessage =
        delegate.verify(json, expectedTopicArn = expectedTopicArn)
}

/** DIRECT/SNS source trust를 codec보다 먼저 고정하는 bounded decoder입니다. */
internal class DefaultAwsModulithInboundSourceDecoder(
    private val sourceMode: AwsModulithSourceMode,
    expectedTopicArns: Set<String>,
    private val codec: AwsModulithEventCodec,
    private val snsVerifier: AwsModulithSnsNotificationVerifier? = null,
) : AwsModulithInboundSourceDecoder {

    private val expectedTopicArns = expectedTopicArns.toSet()

    override fun decode(message: SqsReceivedMessage): AwsModulithDecodedInboundEvent {
        requireRawBodyBound(message.body)
        return when (sourceMode) {
            AwsModulithSourceMode.DIRECT -> decodeDirect(message)
            AwsModulithSourceMode.SNS -> decodeSns(message.body)
        }
    }

    private fun decodeDirect(message: SqsReceivedMessage): AwsModulithDecodedInboundEvent {
        if (SNS_DISCRIMINATOR.containsMatchIn(message.body)) throw AwsModulithSourceException()
        val attributes = sourceBoundary {
            message.messageAttributes.mapValues { (_, value) ->
                require(value.dataType() == "String" && value.stringValue() != null)
                value.stringValue()
            }
        }
        return decodeEnvelope(message.body, attributes)
    }

    private fun decodeSns(json: String): AwsModulithDecodedInboundEvent {
        val preflight = sourceBoundary { SnsNotificationPreflight.parse(json) }
        if (preflight.topicArn !in expectedTopicArns) throw AwsModulithSourceException()
        val verified = sourceBoundary {
            requireNotNull(snsVerifier).verify(json, preflight.topicArn)
        }
        val verifiedEnvelopeMatches = verified.isNotification &&
            verified.topicArn == preflight.topicArn &&
            verified.message == preflight.message
        if (!verifiedEnvelopeMatches) {
            throw AwsModulithSourceException()
        }
        val attributes = sourceBoundary {
            verified.messageAttributes.mapValues { (_, attribute) ->
                require(attribute.type == "String")
                attribute.value
            }
        }
        return decodeEnvelope(verified.message, attributes)
    }

    private fun decodeEnvelope(body: String, attributes: Map<String, String>): AwsModulithDecodedInboundEvent {
        val event = codec.decode(body, attributes)
        val eventId = attributes[DefaultAwsModulithEventCodec.SYSTEM_EVENT_ID]
            ?: throw AwsModulithInboundEnvelopeException()
        val type = attributes[DefaultAwsModulithEventCodec.SYSTEM_EVENT_TYPE]
            ?: throw AwsModulithInboundEnvelopeException()
        return AwsModulithDecodedInboundEvent(event, AwsModulithEventKey(type, eventId))
    }

    private fun requireRawBodyBound(body: String) {
        if (body.isBlank() || body.toByteArray(StandardCharsets.UTF_8).size > MAX_SOURCE_BYTES) {
            throw AwsModulithSourceException()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> sourceBoundary(block: () -> T): T = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Error) {
        throw error
    } catch (_: Throwable) {
        throw AwsModulithSourceException()
    }
}

/**
 * Source decode 이후 claim, heartbeat, synchronous dispatch와 fencing complete를 수행합니다.
 *
 * dispatch 완료는 `ApplicationEventPublisher.publishEvent` 호출이 반환한 시점입니다. 별도 executor나
 * reactive pipeline에서 실행되는 비동기 listener의 최종 완료까지 기다리지는 않습니다. 정상 dispatch와
 * claim complete 뒤에만 caller가 SQS acknowledgement를 수행할 수 있습니다.
 */
@Suppress("LongParameterList", "TooManyFunctions", "TooGenericExceptionCaught")
class AwsModulithSqsEventConsumer internal constructor(
    private val sourceDecoder: AwsModulithInboundSourceDecoder,
    private val registry: AwsModulithEventTypeRegistry,
    private val store: AwsModulithEventIdempotencyStore,
    private val externalization: EventExternalizationConfiguration,
    private val eventPublisher: ApplicationEventPublisher,
    private val properties: AwsModulithEventsProperties.Consumer,
    internal val metrics: AwsModulithMetrics,
    private val clock: Clock,
    private val cleanupTimeout: Duration,
    private val heartbeatInterval: Duration = properties.idempotency.leaseDuration.dividedBy(HEARTBEAT_DIVISOR),
) {
    init {
        require(!cleanupTimeout.isZero && !cleanupTimeout.isNegative)
        require(!heartbeatInterval.isZero && !heartbeatInterval.isNegative)
    }

    suspend fun consume(message: SqsReceivedMessage): AwsModulithConsumeOutcome {
        if ((message.approximateReceiveCount ?: 1) > 1) {
            record(AwsModulithFailurePhase.SOURCE, AwsModulithMetricOutcome.DUPLICATE)
        }
        val decoded = decode(message)
        validateDecodedKey(decoded)
        return when (val claim = claim(decoded.key)) {
            AwsModulithClaimResult.Completed -> {
                record(AwsModulithFailurePhase.CLAIM, AwsModulithMetricOutcome.DUPLICATE)
                AwsModulithConsumeOutcome.COMPLETED_DUPLICATE
            }

            is AwsModulithClaimResult.InProgress -> {
                record(AwsModulithFailurePhase.CLAIM, AwsModulithMetricOutcome.IN_PROGRESS)
                throw AwsModulithEventInProgressException()
            }

            is AwsModulithClaimResult.Acquired -> {
                val outcome = if (claim.token.generation > 1L) {
                    AwsModulithMetricOutcome.TAKEOVER
                } else {
                    AwsModulithMetricOutcome.SUCCESS
                }
                record(AwsModulithFailurePhase.CLAIM, outcome)
                processWithMetrics(decoded.event, claim.token)
            }
        }
    }

    private suspend fun processWithMetrics(event: Any, token: AwsModulithClaimToken): AwsModulithConsumeOutcome {
        val startedAt = System.nanoTime()
        metrics.changeInFlight(AwsModulithMetricService.SQS, 1)
        return try {
            process(event, token).also {
                metrics.recordLatency(
                    AwsModulithMetricService.SQS,
                    AwsModulithFailurePhase.DISPATCH,
                    AwsModulithMetricOutcome.SUCCESS,
                    AwsModulithDiagnosticCode.DISPATCH_ACK,
                    Duration.ofNanos(System.nanoTime() - startedAt),
                )
            }
        } catch (failure: Throwable) {
            metrics.recordLatency(
                AwsModulithMetricService.SQS,
                AwsModulithFailurePhase.DISPATCH,
                AwsModulithMetricOutcome.FAILURE,
                primaryCode(failure),
                Duration.ofNanos(System.nanoTime() - startedAt),
            )
            throw failure
        } finally {
            metrics.changeInFlight(AwsModulithMetricService.SQS, -1)
        }
    }

    private fun decode(message: SqsReceivedMessage): AwsModulithDecodedInboundEvent = try {
        sourceDecoder.decode(message)
    } catch (failure: AwsModulithEventException) {
        record(failure.phase, AwsModulithMetricOutcome.REJECTED, failure.code)
        throw failure
    }

    private fun validateDecodedKey(decoded: AwsModulithDecodedInboundEvent) {
        val registration = registry.registrationFor(decoded.event)
        val expected = AwsModulithEventKey(registration.type, registration.eventId(decoded.event))
        if (decoded.key != expected) throw AwsModulithInboundEnvelopeException()
    }

    private suspend fun claim(key: AwsModulithEventKey): AwsModulithClaimResult = claimBoundary {
        store.claim(key, properties.idempotency.leaseDuration)
    }

    @Suppress("ThrowsCount")
    private suspend fun process(event: Any, initialToken: AwsModulithClaimToken): AwsModulithConsumeOutcome {
        if (!initialToken.leaseUntil.isAfter(clock.instant())) throw AwsModulithStaleClaimException()
        val outboundLoopRisk = try {
            externalization.supports(event)
        } catch (failure: Throwable) {
            failAfterRelease(initialToken, failure)
        }
        if (outboundLoopRisk) {
            failAfterRelease(initialToken, AwsModulithInboundLoopRiskException())
        }

        val latestToken = AtomicReference(initialToken)
        val dispatch = try {
            dispatchWithHeartbeat(event, latestToken)
        } catch (failure: Throwable) {
            failAfterRelease(latestToken.get(), failure)
        }
        if (dispatch.handlerFailure != null) failAfterRelease(dispatch.token, dispatch.handlerFailure)
        dispatch.heartbeatFailure?.let { heartbeatFailure ->
            record(AwsModulithFailurePhase.CLAIM, AwsModulithMetricOutcome.FAILURE, primaryCode(heartbeatFailure))
            when (heartbeatFailure) {
                is CancellationException, is Error -> failAfterRelease(dispatch.token, heartbeatFailure)
                else -> throw heartbeatFailure
            }
        }
        val token = dispatch.token
        val completion = try {
            claimBoundary { store.complete(token) }
        } catch (cancellation: CancellationException) {
            failAfterRelease(token, cancellation)
        }
        if (completion != AwsModulithStoreMutation.APPLIED && completion != AwsModulithStoreMutation.ALREADY_APPLIED) {
            record(AwsModulithFailurePhase.CLAIM, AwsModulithMetricOutcome.FAILURE)
            throw AwsModulithClaimMutationException()
        }
        record(AwsModulithFailurePhase.CLAIM, AwsModulithMetricOutcome.COMPLETED)
        record(AwsModulithFailurePhase.DISPATCH, AwsModulithMetricOutcome.SUCCESS)
        return AwsModulithConsumeOutcome.PROCESSED
    }

    private suspend fun dispatchWithHeartbeat(
        event: Any,
        token: AtomicReference<AwsModulithClaimToken>,
    ): DispatchAttempt = supervisorScope {
        val heartbeatFailure = AtomicReference<Throwable?>()
        val heartbeat = launch {
            try {
                while (isActive) {
                    delay(heartbeatInterval.toMillis())
                    token.set(claimBoundary { store.renew(token.get(), properties.idempotency.leaseDuration) })
                }
            } catch (cancellation: CancellationException) {
                if (currentCoroutineContext().isActive) {
                    heartbeatFailure.compareAndSet(null, cancellation)
                } else {
                    throw cancellation
                }
            } catch (failure: Throwable) {
                heartbeatFailure.compareAndSet(null, failure)
            }
        }

        val handlerFailure = try {
            eventPublisher.publishEvent(event)
            null
        } catch (failure: Throwable) {
            failure
        }
        withContext(NonCancellable) {
            heartbeat.cancelAndJoin()
        }
        DispatchAttempt(
            token = token.get(),
            handlerFailure = handlerFailure,
            heartbeatFailure = heartbeatFailure.get(),
        )
    }

    private suspend fun failAfterRelease(token: AwsModulithClaimToken, failure: Throwable): Nothing {
        val primary = sanitizeDispatchFailure(failure)
        cleanupFailure(token)?.let(primary::addSuppressed)
        record(primaryPhase(primary), AwsModulithMetricOutcome.FAILURE, primaryCode(primary))
        throw primary
    }

    /** Store SPI의 non-blocking, cancellation-cooperative suspend 계약 안에서 cleanup을 제한합니다. */
    private suspend fun cleanupFailure(token: AwsModulithClaimToken): AwsModulithCleanupException? = try {
        withContext(NonCancellable) {
            withTimeout(cleanupTimeout.toMillis()) {
                val result = store.release(token)
                if (result == AwsModulithStoreMutation.STALE) throw AwsModulithCleanupException()
            }
        }
        null
    } catch (_: Throwable) {
        AwsModulithCleanupException()
    }

    private fun sanitizeDispatchFailure(failure: Throwable): Throwable = when (failure) {
        is CancellationException -> failure
        is Error -> failure
        is AwsModulithInboundLoopRiskException -> failure
        else -> AwsModulithDispatchException()
    }

    private suspend fun <T> claimBoundary(block: suspend () -> T): T = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Error) {
        throw error
    } catch (failure: AwsModulithEventException) {
        val outcome = if (failure is AwsModulithClaimCapacityException) {
            AwsModulithMetricOutcome.REJECTED
        } else {
            AwsModulithMetricOutcome.FAILURE
        }
        record(AwsModulithFailurePhase.CLAIM, outcome, failure.code)
        throw failure
    } catch (_: Throwable) {
        record(AwsModulithFailurePhase.CLAIM, AwsModulithMetricOutcome.FAILURE)
        throw AwsModulithClaimMutationException()
    }

    private fun record(
        phase: AwsModulithFailurePhase,
        outcome: AwsModulithMetricOutcome,
        code: AwsModulithDiagnosticCode = phase.defaultCode(),
    ) {
        metrics.record(AwsModulithMetricService.SQS, phase, outcome, code)
    }

    private fun primaryPhase(primary: Throwable): AwsModulithFailurePhase =
        (primary as? AwsModulithEventException)?.phase ?: AwsModulithFailurePhase.DISPATCH

    private fun primaryCode(primary: Throwable): AwsModulithDiagnosticCode =
        (primary as? AwsModulithEventException)?.code ?: AwsModulithDiagnosticCode.DISPATCH_ACK

    companion object {
        private const val HEARTBEAT_DIVISOR = 3L

        internal fun cleanupTimeout(sqsStopTimeoutMillis: Long, leaseDuration: Duration): Duration =
            Duration.ofMillis(
                minOf(sqsStopTimeoutMillis, leaseDuration.toMillis() / HEARTBEAT_DIVISOR).coerceAtLeast(1)
            )
    }

    private data class DispatchAttempt(
        val token: AwsModulithClaimToken,
        val handlerFailure: Throwable?,
        val heartbeatFailure: Throwable?,
    )
}

private object SnsNotificationPreflight {
    private val mapper = ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(
                StreamReadConstraints.builder()
                    .maxNestingDepth(MAX_SOURCE_DEPTH)
                    .maxTokenCount(MAX_SOURCE_TOKENS)
                    .maxStringLength(MAX_SOURCE_STRING_LENGTH)
                    .maxNameLength(MAX_SOURCE_STRING_LENGTH)
                    .maxNumberLength(MAX_SOURCE_NUMBER_LENGTH)
                    .build()
            )
            .build()
    )

    @Suppress("CyclomaticComplexMethod", "ThrowsCount")
    fun parse(json: String): SnsNotificationFields {
        validateAllTokens(json)
        val parser = mapper.createParser(json)
        parser.use {
            if (it.nextToken() != JsonToken.START_OBJECT) throw AwsModulithSourceException()
            val fields = HashSet<String>()
            var type: String? = null
            var topicArn: String? = null
            var message: String? = null
            while (true) {
                when (it.nextToken() ?: throw AwsModulithSourceException()) {
                    JsonToken.PROPERTY_NAME -> {
                        val (name, value) = it.readProperty(fields)
                        when (name) {
                            "Type" -> type = value
                            "TopicArn" -> topicArn = value
                            "Message" -> message = value
                            else -> Unit
                        }
                    }

                    JsonToken.END_OBJECT -> break
                    else -> throw AwsModulithSourceException()
                }
            }
            if (it.nextToken() != null || type != "Notification" || !fields.containsAll(SNS_REQUIRED_FIELDS)) {
                throw AwsModulithSourceException()
            }
            return SnsNotificationFields(
                topicArn = topicArn?.takeIf(String::isNotBlank) ?: throw AwsModulithSourceException(),
                message = message?.takeIf(String::isNotBlank) ?: throw AwsModulithSourceException(),
            )
        }
    }

    private fun validateAllTokens(json: String) {
        mapper.createParser(json).use { parser ->
            while (true) {
                when (parser.nextToken() ?: break) {
                    JsonToken.PROPERTY_NAME, JsonToken.VALUE_STRING,
                    JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT,
                    -> parser.getString().requireUtf8Bound()

                    else -> Unit
                }
            }
        }
    }

    private fun JsonParser.requiredNonBlankString(token: JsonToken): String {
        if (token != JsonToken.VALUE_STRING) throw AwsModulithSourceException()
        return getString().takeIf(String::isNotBlank) ?: throw AwsModulithSourceException()
    }

    private fun String.requireUtf8Bound() {
        if (toByteArray(StandardCharsets.UTF_8).size > MAX_SOURCE_STRING_LENGTH) {
            throw AwsModulithSourceException()
        }
    }

    @Suppress("ThrowsCount")
    private fun JsonParser.readProperty(fields: MutableSet<String>): Pair<String, String?> {
        val name = currentName() ?: throw AwsModulithSourceException()
        if (name !in SNS_NOTIFICATION_FIELDS || !fields.add(name)) throw AwsModulithSourceException()
        val token = nextToken() ?: throw AwsModulithSourceException()
        val value = if (name in SNS_REQUIRED_FIELDS) {
            requiredNonBlankString(token)
        } else {
            skipChildren()
            null
        }
        return name to value
    }
}

private data class SnsNotificationFields(val topicArn: String, val message: String)

private fun AwsModulithFailurePhase.defaultCode(): AwsModulithDiagnosticCode = when (this) {
    AwsModulithFailurePhase.SOURCE -> AwsModulithDiagnosticCode.SOURCE
    AwsModulithFailurePhase.DECODE -> AwsModulithDiagnosticCode.INBOUND
    AwsModulithFailurePhase.CLAIM -> AwsModulithDiagnosticCode.CLAIM
    AwsModulithFailurePhase.DISPATCH, AwsModulithFailurePhase.ACK, AwsModulithFailurePhase.CLEANUP ->
        AwsModulithDiagnosticCode.DISPATCH_ACK
    AwsModulithFailurePhase.CONFIGURATION -> AwsModulithDiagnosticCode.CONFIGURATION
    AwsModulithFailurePhase.SERIALIZATION -> AwsModulithDiagnosticCode.ENVELOPE
    AwsModulithFailurePhase.LIFECYCLE -> AwsModulithDiagnosticCode.PRODUCER_LIFECYCLE
    AwsModulithFailurePhase.RESOLUTION, AwsModulithFailurePhase.PUBLISH -> AwsModulithDiagnosticCode.AWS_PUBLISH
}

private const val MAX_SOURCE_BYTES = 262_144
private const val MAX_SOURCE_DEPTH = 32
private const val MAX_SOURCE_TOKENS = 100_000L
private const val MAX_SOURCE_STRING_LENGTH = 196_608
private const val MAX_SOURCE_NUMBER_LENGTH = 1_000
private val SNS_DISCRIMINATOR = Regex(
    "\\\"Type\\\"\\s*:\\s*\\\"(?:Notification|SubscriptionConfirmation|UnsubscribeConfirmation)\\\""
)
private val SNS_REQUIRED_FIELDS = setOf(
    "Type", "MessageId", "TopicArn", "Message", "Timestamp", "SignatureVersion", "Signature", "SigningCertURL"
)
private val SNS_NOTIFICATION_FIELDS = SNS_REQUIRED_FIELDS + setOf("Subject", "UnsubscribeURL", "MessageAttributes")
