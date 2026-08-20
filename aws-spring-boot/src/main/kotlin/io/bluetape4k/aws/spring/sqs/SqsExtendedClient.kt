@file:Suppress("LargeClass", "LongMethod", "SwallowedException")

package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.aws.spring.s3.S3BoundedEncryptedReadOperations
import io.bluetape4k.aws.spring.s3.S3BoundedObjectReadOperations
import io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionIdentity
import io.bluetape4k.aws.spring.s3.S3ObjectMetadataOperations
import io.bluetape4k.aws.spring.s3.S3Operations
import io.bluetape4k.aws.spring.s3.S3PutIfAbsentResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import software.amazon.awssdk.services.sqs.model.SendMessageResponse
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val POINTER_PREFIX = "bt4k-sqs-extended/v1."
private const val MAX_WAIT_TIME_SECONDS = 20
private const val MIN_VISIBILITY_TIMEOUT_SECONDS = 1
private const val MAX_VISIBILITY_TIMEOUT_SECONDS = 43_200
private const val ENCRYPTION_TAG_BYTES = 16
private const val MARKER_VERSION = "1"
private const val MARKER_CONTENT_TYPE = "application/octet-stream"
private const val MARKER_VERSION_METADATA = "bt4k-marker-version"
private const val MARKER_POINTER_DIGEST_METADATA = "bt4k-pointer-digest"
private const val MARKER_POLICY_FINGERPRINT_METADATA = "bt4k-policy-fingerprint"
private const val MARKER_QUEUE_DIGEST_METADATA = "bt4k-queue-url-digest"

@Suppress("TooManyFunctions")
class SqsExtendedClient(
    private val sqsOperations: SqsFullRequestOperations,
    private val s3Operations: S3Operations,
    private val boundedS3Operations: S3BoundedObjectReadOperations?,
    private val s3MetadataOperations: S3ObjectMetadataOperations?,
    private val encryptedS3Operations: S3BoundedEncryptedReadOperations?,
    private val encryptionIdentity: S3ClientSideEncryptionIdentity?,
    private val properties: SqsExtendedClientProperties,
    private val metrics: SqsExtendedClientMetrics? = null,
) : SqsExtendedClientOperations {

    private val activeOperations = AtomicInteger(0)
    private val acceptingOperations = AtomicReference(true)
    private val lifecycleFailure = AtomicReference<SqsExtendedDrainTimeoutException?>(null)
    private val admissionLock = Any()

    override suspend fun send(request: SqsExtendedSendRequest): SqsExtendedSendResult =
        withOperation {
            requireEnabled(producer = true)
            val policy = properties.resolvePolicy(request.request.queueUrl)
            if (!properties.producerEnabled || policy == null) {
                return@withOperation inlineSend(request)
            }

            val payload = strictUtf8(request.request.body)
            if (payload.size <= policy.offloadThresholdBytes) {
                return@withOperation inlineSend(request)
            }
            val idempotencyKey = request.idempotencyKey
                ?: run {
                    metrics?.recordOffloadRejected()
                    throw SqsExtendedConfigurationException.create()
                }
            if (payload.size > policy.maxOffloadPayloadBytes) {
                metrics?.recordOffloadRejected()
                throw SqsExtendedConfigurationException.create()
            }
            val signingKey = resolveSigningKey(policy)
            val payloadDigest = sha256(payload)
            val objectKey = buildObjectKey(policy, request.request.queueUrl, idempotencyKey, payloadDigest)
            val policyFingerprint = policyFingerprint(request.request.queueUrl)
            val pointerEnvelope = SqsExtendedPointerCodec.encode(
                bucket = policy.bucket,
                key = objectKey,
                contentType = request.contentType,
                encrypted = policy.encryption.enabled,
                queueUrl = request.request.queueUrl,
                policyFingerprint = policyFingerprint,
                signingKey = signingKey,
            )
            val pointer = SqsExtendedPointerCodec.decode(
                pointerEnvelope,
                request.request.queueUrl,
                policyFingerprint,
                signingKey,
            )
            try {
                uploadPayload(policy, objectKey, payload, request.contentType)
            } catch (cancelled: CancellationException) {
                metrics?.recordOrphanCancelled()
                throw cancelled
            } catch (configuration: SqsExtendedClientException) {
                metrics?.recordOffloadRejected()
                throw configuration
            } catch (_: Throwable) {
                metrics?.recordOffloadRejected()
                throw SqsExtendedSendException.upload()
            }

            val response = try {
                sqsOperations.send(request.request.copy(body = pointerEnvelope))
            } catch (cancelled: CancellationException) {
                metrics?.recordOrphanCancelled()
                throw SqsExtendedCancellationException.create(
                    failureKind = SqsExtendedFailureKind.SQS_SEND,
                    pointerPresent = true,
                    orphanCleanupRequired = true,
                )
            } catch (_: Throwable) {
                metrics?.recordOrphanSqsSend()
                throw SqsExtendedSendException.offloadedSqs()
            }
            metrics?.recordOffloadSuccess()
            return@withOperation SqsExtendedSendResult.create(
                response = response.toExtendedResponse(),
                offloaded = true,
                pointer = pointer,
            )
        }

    override suspend fun receive(
        queueUrl: String,
        maxMessages: Int,
        waitTimeSeconds: Int,
        visibilityTimeoutSeconds: Int,
    ): List<SqsExtendedReceivedMessage> = withOperation {
        requireEnabled(producer = false)
        val policy = properties.resolvePolicy(queueUrl)
        validateReceiveArguments(maxMessages, waitTimeSeconds, visibilityTimeoutSeconds, policy)
        val messages = sqsOperations.receive(queueUrl, 1, waitTimeSeconds, visibilityTimeoutSeconds)
        messages.map { message -> restore(queueUrl, policy, message) }
    }

    override fun receiveFlow(
        queueUrl: String,
        maxMessages: Int,
        waitTimeSeconds: Int,
        visibilityTimeoutSeconds: Int,
): Flow<SqsExtendedReceivedMessage> = flow {
        receive(queueUrl, maxMessages, waitTimeSeconds, visibilityTimeoutSeconds).forEach { emit(it) }
    }

    override suspend fun acknowledge(message: SqsExtendedReceivedMessage): SqsExtendedAcknowledgementResult =
        withOperation {
            val acknowledgement = message.acknowledgementToken()
            val policy = properties.resolvePolicy(acknowledgement.queueUrl)
            validateAcknowledgement(message, acknowledgement, policy)
            try {
                sqsOperations.delete(acknowledgement.queueUrl, acknowledgement.receiptHandle)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                throw SqsExtendedAcknowledgementException.create(
                    sqsDeleted = false,
                    cleanupRequired = false,
                )
            }

            val pointer = message.pointer
            if (!requiresPayloadCleanup(pointer, message.duplicateAfterCleanup, policy)) {
                return@withOperation SqsExtendedAcknowledgementResult.create(
                    sqsDeleted = true,
                    payloadDeleted = false,
                    cleanupRequired = false,
                    pointer = pointer,
                )
            }
            if (s3MetadataOperations == null) {
                metrics?.recordCleanupFailure(SqsExtendedClientMetrics.CleanupFailureKind.CONFIGURATION)
                throw SqsExtendedCleanupException.configuration()
            }

            var cancellation: CancellationException? = null
            try {
                currentCoroutineContext().ensureActive()
            } catch (cancelled: CancellationException) {
                cancellation = cancelled
            }
            val result = try {
                withContext(NonCancellable) {
                    acknowledgePayload(
                        requireNotNull(pointer),
                        acknowledgement,
                        requireNotNull(policy),
                    )
                }
            } catch (failure: SqsExtendedCleanupException) {
                metrics?.recordCleanupFailure(
                    if (failure.failureKind == SqsExtendedFailureKind.S3_DELETE) {
                        SqsExtendedClientMetrics.CleanupFailureKind.S3_DELETE
                    } else {
                        SqsExtendedClientMetrics.CleanupFailureKind.CONFIGURATION
                    },
                )
                throw failure
            }
            if (cancellation == null) {
                try {
                    currentCoroutineContext().ensureActive()
                } catch (cancelled: CancellationException) {
                    cancellation = cancelled
                }
            }
            cancellation?.let { throw it }
            if (result.cleanupRequired) {
                metrics?.recordCleanupFailure(SqsExtendedClientMetrics.CleanupFailureKind.S3_DELETE)
            }
            result
        }

    override suspend fun cleanup(handle: SqsExtendedCleanupHandle): SqsExtendedCleanupResult =
        withOperation {
            val policy = properties.resolvePolicy(handle.queueUrl)
            if (
                policy == null ||
                !policy.deleteOnAck ||
                handle.policyFingerprint != policyFingerprint(handle.queueUrl)
            ) {
                metrics?.recordCleanupFailure(SqsExtendedClientMetrics.CleanupFailureKind.CONFIGURATION)
                throw SqsExtendedCleanupException.configuration(handlePresent = true)
            }
            val metadata = s3MetadataOperations ?: run {
                metrics?.recordCleanupFailure(SqsExtendedClientMetrics.CleanupFailureKind.CONFIGURATION)
                throw SqsExtendedCleanupException.configuration(handlePresent = true)
            }
            val markerKey = handle.markerKey ?: run {
                metrics?.recordCleanupFailure(SqsExtendedClientMetrics.CleanupFailureKind.CONFIGURATION)
                throw SqsExtendedCleanupException.configuration(handlePresent = true)
            }
            val pointerDigest = handle.pointerDigest
                ?: run {
                    metrics?.recordCleanupFailure(SqsExtendedClientMetrics.CleanupFailureKind.CONFIGURATION)
                    throw SqsExtendedCleanupException.configuration(handlePresent = true)
                }
            val expectedMetadata = markerMetadata(handle.queueUrl, handle.policyFingerprint, pointerDigest)
            try {
                verifyExistingMarker(metadata, policy.bucket, markerKey, expectedMetadata, handlePresent = true)
            } catch (failure: SqsExtendedCleanupException) {
                metrics?.recordCleanupFailure(SqsExtendedClientMetrics.CleanupFailureKind.CONFIGURATION)
                throw failure
            }
            try {
                s3Operations.delete(policy.bucket, handle.pointer.key)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                metrics?.recordCleanupFailure(SqsExtendedClientMetrics.CleanupFailureKind.S3_DELETE)
                return@withOperation cleanupRetryResult(handle)
            }
            SqsExtendedCleanupResult.create(deleted = true, cleanupRequired = false)
        }

    override suspend fun drain(timeout: Duration?): SqsExtendedDrainResult {
        val configuredTimeout = properties.shutdownDrainTimeoutSeconds.toLong().seconds
        val effectiveTimeout = timeout ?: configuredTimeout
        if (effectiveTimeout <= Duration.ZERO || effectiveTimeout > configuredTimeout) configurationError()
        val activeAtStart = synchronized(admissionLock) {
            acceptingOperations.set(false)
            activeOperations.get()
        }
        val completed = suspend {
            while (activeOperations.get() > 0) {
                delay(1)
            }
            activeAtStart
        }
        val completedCount = withTimeoutOrNull(effectiveTimeout) { completed() }
            ?: (activeAtStart - activeOperations.get())
        val timedOut = activeOperations.get() > 0
        if (timedOut) {
            lifecycleFailure.set(SqsExtendedDrainTimeoutException(activeOperations.get()))
        }
        return SqsExtendedDrainResult(activeAtStart, completedCount, timedOut)
    }

    internal fun policyFingerprint(queueUrl: String): String {
        val policy = properties.resolvePolicy(queueUrl) ?: throw SqsExtendedConfigurationException.create()
        return SqsExtendedPolicyFingerprint.calculate(queueUrl, policy)
    }

    internal fun lastLifecycleFailure(): SqsExtendedDrainTimeoutException? = lifecycleFailure.get()

    internal suspend fun stopForSpring(
        timeout: Duration,
        onDrained: () -> Unit,
        onTimeout: (Int) -> Unit,
    ) {
        val result = drain(timeout)
        if (result.timedOut) {
            onTimeout(activeOperations.get())
        } else {
            onDrained()
        }
    }

    internal fun recordLifecycleFailure(failure: SqsExtendedDrainTimeoutException) {
        lifecycleFailure.set(failure)
    }

    private fun validateAcknowledgement(
        message: SqsExtendedReceivedMessage,
        acknowledgement: SqsExtendedAcknowledgementToken,
        policy: SqsExtendedClientProperties.Policy?,
    ) {
        if (message.pointer == null) return
        if (policy == null || acknowledgement.policyFingerprint != policyFingerprint(acknowledgement.queueUrl)) {
            throw SqsExtendedCleanupException.configuration()
        }
        if (acknowledgement.pointerDigest == null) throw SqsExtendedCleanupException.configuration()
    }

    private fun requiresPayloadCleanup(
        pointer: SqsExtendedClientPointer?,
        duplicateAfterCleanup: Boolean,
        policy: SqsExtendedClientProperties.Policy?,
    ): Boolean = pointer != null && !duplicateAfterCleanup && policy?.deleteOnAck == true

    private suspend fun acknowledgePayload(
        pointer: SqsExtendedClientPointer,
        acknowledgement: SqsExtendedAcknowledgementToken,
        policy: SqsExtendedClientProperties.Policy,
    ): SqsExtendedAcknowledgementResult {
        val metadata = requireNotNull(s3MetadataOperations)
        val markerKey = markerKey(policy, pointer)
        val pointerDigest = requireNotNull(acknowledgement.pointerDigest)
        val expectedMetadata = markerMetadata(
            acknowledgement.queueUrl,
            requireNotNull(acknowledgement.policyFingerprint),
            pointerDigest,
        )
        ensureMarkerOrRetry(metadata, policy.bucket, markerKey, expectedMetadata, pointer, acknowledgement)?.let {
            return it
        }

        return try {
            s3Operations.delete(policy.bucket, pointer.key)
            SqsExtendedAcknowledgementResult.create(
                sqsDeleted = true,
                payloadDeleted = true,
                cleanupRequired = false,
                pointer = pointer,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            acknowledgementCleanupResult(pointer, acknowledgement, markerKey)
        }
    }

    private suspend fun ensureMarkerOrRetry(
        metadata: S3ObjectMetadataOperations,
        bucket: String,
        markerKey: String,
        expectedMetadata: Map<String, String>,
        pointer: SqsExtendedClientPointer,
        acknowledgement: SqsExtendedAcknowledgementToken,
    ): SqsExtendedAcknowledgementResult? = try {
        ensureMarker(metadata, bucket, markerKey, expectedMetadata)
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (configuration: SqsExtendedCleanupException) {
        throw configuration
    } catch (_: RuntimeException) {
        acknowledgementCleanupResult(pointer, acknowledgement, markerKey)
    }

    private suspend fun ensureMarker(
        metadata: S3ObjectMetadataOperations,
        bucket: String,
        markerKey: String,
        expectedMetadata: Map<String, String>,
    ) {
        val existing = try {
            metadata.headObjectWithMetadata(bucket, markerKey)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        if (existing != null) {
            verifyMarkerMetadata(existing.userMetadata, expectedMetadata, handlePresent = false)
            return
        }
        when (val result = metadata.putObjectIfAbsentWithMetadata(
            bucket = bucket,
            key = markerKey,
            bytes = ByteArray(0),
            contentType = MARKER_CONTENT_TYPE,
            metadata = expectedMetadata,
        )) {
            S3PutIfAbsentResult.Created -> Unit
            is S3PutIfAbsentResult.AlreadyExists ->
                verifyMarkerMetadata(result.metadata.userMetadata, expectedMetadata, handlePresent = false)
        }
    }

    private suspend fun verifyExistingMarker(
        metadata: S3ObjectMetadataOperations,
        bucket: String,
        markerKey: String,
        expectedMetadata: Map<String, String>,
        handlePresent: Boolean,
    ) {
        val existing = try {
            metadata.headObjectWithMetadata(bucket, markerKey)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            throw SqsExtendedCleanupException.configuration(handlePresent)
        }
        verifyMarkerMetadata(existing.userMetadata, expectedMetadata, handlePresent)
    }

    private fun verifyMarkerMetadata(
        actual: Map<String, String>,
        expected: Map<String, String>,
        handlePresent: Boolean,
    ) {
        val matches = expected.all { (name, value) -> constantTimeEquals(actual[name], value) }
        if (!matches) throw SqsExtendedCleanupException.configuration(handlePresent)
    }

    private fun acknowledgementCleanupResult(
        pointer: SqsExtendedClientPointer,
        acknowledgement: SqsExtendedAcknowledgementToken,
        markerKey: String,
    ): SqsExtendedAcknowledgementResult {
        val handle = SqsExtendedCleanupHandle.create(
            pointer = pointer,
            queueUrl = acknowledgement.queueUrl,
            policyFingerprint = requireNotNull(acknowledgement.policyFingerprint),
            markerKey = markerKey,
            pointerDigest = acknowledgement.pointerDigest,
        )
        return SqsExtendedAcknowledgementResult.create(
            sqsDeleted = true,
            payloadDeleted = false,
            cleanupRequired = true,
            pointer = pointer,
            failureKind = SqsExtendedFailureKind.S3_DELETE,
            retryable = true,
            cleanupHandle = handle,
        )
    }

    private fun cleanupRetryResult(handle: SqsExtendedCleanupHandle): SqsExtendedCleanupResult =
        SqsExtendedCleanupResult.create(
            deleted = false,
            cleanupRequired = true,
            failureKind = SqsExtendedFailureKind.S3_DELETE,
            retryable = true,
            diagnostic = SqsExtendedDiagnosticCode.S3_DELETE,
            cleanupHandle = handle,
        )

    private fun markerKey(
        policy: SqsExtendedClientProperties.Policy,
        pointer: SqsExtendedClientPointer,
    ): String =
        "${policy.normalizedKeyPrefix()}.ack-marker/${sha256("${pointer.bucket}|${pointer.key}".toByteArray(StandardCharsets.UTF_8))}"

    private fun markerMetadata(
        queueUrl: String,
        policyFingerprint: String,
        pointerDigest: String,
    ): Map<String, String> = mapOf(
        MARKER_VERSION_METADATA to MARKER_VERSION,
        MARKER_POINTER_DIGEST_METADATA to pointerDigest,
        MARKER_POLICY_FINGERPRINT_METADATA to policyFingerprint,
        MARKER_QUEUE_DIGEST_METADATA to sha256(queueUrl.toByteArray(StandardCharsets.UTF_8)),
    )

    private fun constantTimeEquals(actual: String?, expected: String): Boolean =
        actual != null && MessageDigest.isEqual(
            actual.toByteArray(StandardCharsets.UTF_8),
            expected.toByteArray(StandardCharsets.UTF_8),
        )

    private suspend fun inlineSend(request: SqsExtendedSendRequest): SqsExtendedSendResult {
        val response = try {
            sqsOperations.send(request.request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                throw SqsExtendedSendException.inlineSqs()
            }
        metrics?.recordOffloadInline()
        return SqsExtendedSendResult.create(
            response = response.toExtendedResponse(),
            offloaded = false,
            pointer = null,
        )
    }

    private suspend fun uploadPayload(
        policy: SqsExtendedClientProperties.Policy,
        objectKey: String,
        payload: ByteArray,
        contentType: String?,
    ) {
        if (!policy.encryption.enabled) {
            s3Operations.upload(policy.bucket, objectKey, payload, contentType)
            return
        }
        val encrypted = encryptedS3Operations ?: configurationError()
        val identity = encryptionIdentity ?: configurationError()
        if (identity.keyFingerprint != policy.encryption.keyFingerprint) {
            configurationError()
        }
        encrypted.uploadEncrypted(
            bucket = policy.bucket,
            key = objectKey,
            bytes = payload,
            contentType = contentType,
            encryptionContext = policy.encryption.encryptionContext,
        )
    }

    private suspend fun restore(
        queueUrl: String,
        policy: SqsExtendedClientProperties.Policy?,
        message: SqsReceivedMessage,
    ): SqsExtendedReceivedMessage {
        val body = message.body
        return try {
            val fingerprint = policy?.let { policyFingerprint(queueUrl) }
            val pointer = decodePointer(queueUrl, policy, body, fingerprint)
            val restoredBody = pointer?.let { restorePayload(requireNotNull(policy), it) } ?: body

            SqsExtendedReceivedMessage.create(
                message = message,
                body = restoredBody,
                contentType = pointer?.contentType,
                pointer = pointer,
                duplicateAfterCleanup = false,
                acknowledgement = SqsExtendedAcknowledgementToken(
                    queueUrl = queueUrl,
                    receiptHandle = message.receiptHandle,
                    pointerDigest = pointer?.let { sha256(body.toByteArray(StandardCharsets.UTF_8)) },
                    policyFingerprint = fingerprint,
                ),
            )
        } catch (failure: SqsExtendedClientException) {
            metrics?.recordPayloadReadFailure(
                when (failure.failureKind) {
                    SqsExtendedFailureKind.POINTER_FORMAT ->
                        SqsExtendedClientMetrics.PayloadReadFailureKind.POINTER_FORMAT
                    SqsExtendedFailureKind.CONFIGURATION ->
                        SqsExtendedClientMetrics.PayloadReadFailureKind.CONFIGURATION
                    else -> SqsExtendedClientMetrics.PayloadReadFailureKind.S3_READ
                },
            )
            throw failure
        }
    }

    private fun decodePointer(
        queueUrl: String,
        policy: SqsExtendedClientProperties.Policy?,
        body: String,
        fingerprint: String?,
    ): SqsExtendedClientPointer? {
        if (policy == null || !body.startsWith(POINTER_PREFIX)) return null
        val decoded = SqsExtendedPointerCodec.decode(
            body,
            queueUrl,
            requireNotNull(fingerprint),
            resolveSigningKey(policy),
        )
        if (decoded.bucket != policy.bucket || !decoded.key.startsWith(policy.normalizedKeyPrefix())) {
            throw SqsExtendedPointerFormatException.create()
        }
        return decoded
    }

    private suspend fun restorePayload(
        policy: SqsExtendedClientProperties.Policy,
        pointer: SqsExtendedClientPointer,
    ): String = try {
        if (pointer.encrypted) {
            restoreEncryptedPayload(policy, pointer)
        } else {
            restorePlainPayload(policy, pointer)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: SqsExtendedClientException) {
        throw failure
    } catch (_: Throwable) {
        throw SqsExtendedPayloadReadException.create(pointerPresent = true, retryable = true)
    }

    private suspend fun restoreEncryptedPayload(
        policy: SqsExtendedClientProperties.Policy,
        pointer: SqsExtendedClientPointer,
    ): String {
        validateEncryption(policy)
        val encrypted = encryptedS3Operations
            ?: throw SqsExtendedPayloadReadException.create(pointerPresent = true, retryable = false)
        val payload = encrypted.downloadEncryptedBytesBounded(
            pointer.bucket,
            pointer.key,
            policy.encryption.encryptionContext,
            policy.maxOffloadPayloadBytes + ENCRYPTION_TAG_BYTES,
        )
        return strictDecode(payload)
    }

    private fun validateEncryption(policy: SqsExtendedClientProperties.Policy) {
        if (!policy.encryption.enabled) configurationError()
        val identity = encryptionIdentity ?: configurationError()
        if (identity.keyFingerprint != policy.encryption.keyFingerprint) {
            configurationError()
        }
    }

    private suspend fun restorePlainPayload(
        policy: SqsExtendedClientProperties.Policy,
        pointer: SqsExtendedClientPointer,
    ): String {
        val bounded = boundedS3Operations
            ?: throw SqsExtendedPayloadReadException.create(pointerPresent = true, retryable = false)
        return strictDecode(
            bounded.downloadBytesBounded(pointer.bucket, pointer.key, policy.maxOffloadPayloadBytes),
        )
    }

    private suspend fun <T> withOperation(block: suspend () -> T): T {
        synchronized(admissionLock) {
            if (!acceptingOperations.get()) {
                throw SqsExtendedConfigurationException.drainAdmission()
            }
            activeOperations.incrementAndGet()
        }
        try {
            return block()
        } finally {
            activeOperations.decrementAndGet()
        }
    }

    private fun requireEnabled(producer: Boolean) {
        if (!properties.enabled) configurationError()
        if (producer && !properties.producerEnabled) configurationError()
        if (!producer && !properties.consumerEnabled) configurationError()
    }

    private fun validateReceiveArguments(
        maxMessages: Int,
        waitTimeSeconds: Int,
        visibilityTimeoutSeconds: Int,
        policy: SqsExtendedClientProperties.Policy?,
    ) {
        if (maxMessages != 1) configurationError()
        if (waitTimeSeconds !in 0..MAX_WAIT_TIME_SECONDS) configurationError()
        if (visibilityTimeoutSeconds !in MIN_VISIBILITY_TIMEOUT_SECONDS..MAX_VISIBILITY_TIMEOUT_SECONDS) {
            configurationError()
        }
        if (policy != null && visibilityTimeoutSeconds < policy.minimumVisibilityTimeoutSeconds) {
            configurationError()
        }
    }

    private fun configurationError(): Nothing = throw SqsExtendedConfigurationException.create()

    private fun resolveSigningKey(policy: SqsExtendedClientProperties.Policy): ByteArray =
        try {
            properties.security.resolveSigningKey(policy.pointerSigningKeyRef)
        } catch (_: Throwable) {
            throw SqsExtendedConfigurationException.create()
        }

    private fun buildObjectKey(
        policy: SqsExtendedClientProperties.Policy,
        queueUrl: String,
        idempotencyKey: String,
        payloadDigest: String,
    ): String =
        "${policy.normalizedKeyPrefix()}${sha256("$queueUrl|$idempotencyKey|$payloadDigest".toByteArray(StandardCharsets.UTF_8))}"

    private fun strictUtf8(value: String): ByteArray = try {
        val encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val encoded = encoder.encode(CharBuffer.wrap(value))
        ByteArray(encoded.remaining()).also { encoded.get(it) }
    } catch (_: CharacterCodingException) {
        throw SqsExtendedConfigurationException.create()
    }

    private fun strictDecode(bytes: ByteArray): String = try {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        throw SqsExtendedPayloadReadException.create(pointerPresent = true, retryable = false)
    }

    private fun sha256(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(bytes),
        )
}

private fun SendMessageResponse.toExtendedResponse(): SqsExtendedSendResponse =
    SqsExtendedSendResponse(
        messageId = messageId(),
        sequenceNumber = sequenceNumber(),
        md5OfMessageBody = md5OfMessageBody(),
        md5OfMessageAttributes = md5OfMessageAttributes(),
    )
