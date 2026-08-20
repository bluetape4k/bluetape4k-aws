package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.codec.Base58
import org.junit.jupiter.api.Test
import java.util.Base64

class SqsExtendedClientPointerTest {

    @Test
    fun `pointer factory exposes only validated values and stable value equality`() {
        val bucket = "bucket-${Base58.randomString(16)}"
        val key = "key/${Base58.randomString(16)}"
        val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })
        val first = SqsExtendedClientPointer.create(bucket, key, "application/json", encrypted = false, signature)
        val second = SqsExtendedClientPointer.create(bucket, key, "application/json", encrypted = false, signature)

        first shouldBeEqualTo second
        first.bucket shouldBeEqualTo bucket
        first.key shouldBeEqualTo key
        first.toString() shouldNotContain bucket
        first.toString() shouldNotContain key
        first.toString() shouldNotContain signature
    }

    @Test
    fun `pointer factory rejects control characters and non canonical signatures`() {
        val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 9 })
        assertFailsWith<IllegalArgumentException> {
            SqsExtendedClientPointer.create("bucket\n", "key", null, false, signature)
        }
        assertFailsWith<IllegalArgumentException> {
            SqsExtendedClientPointer.create("bucket", "key\u0000", null, false, signature)
        }
        assertFailsWith<IllegalArgumentException> {
            SqsExtendedClientPointer.create("bucket", "key", "text\rplain", false, signature)
        }
        assertFailsWith<IllegalArgumentException> {
            SqsExtendedClientPointer.create("bucket", "key", null, false, "not canonical+")
        }
    }

    @Test
    fun `pointer codec authenticates queue and policy bindings`() {
        val key = ByteArray(32) { it.toByte() }
        val queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789012/${Base58.randomString(16)}"
        val policyFingerprint = "fingerprint-${Base58.randomString(16)}"
        val encoded = SqsExtendedPointerCodec.encode(
            bucket = "bucket-${Base58.randomString(16)}",
            key = "payload/${Base58.randomString(16)}",
            contentType = "application/json",
            encrypted = false,
            queueUrl = queueUrl,
            policyFingerprint = policyFingerprint,
            signingKey = key,
        )
        val decoded = SqsExtendedPointerCodec.decode(encoded, queueUrl, policyFingerprint, key)
        decoded.encrypted shouldBeEqualTo false
        decoded.contentType shouldBeEqualTo "application/json"

        assertFailsWith<SqsExtendedPointerFormatException> {
            SqsExtendedPointerCodec.decode(encoded, "$queueUrl/foreign", policyFingerprint, key)
        }
        assertFailsWith<SqsExtendedPointerFormatException> {
            SqsExtendedPointerCodec.decode(encoded, queueUrl, "$policyFingerprint/foreign", key)
        }
        assertFailsWith<SqsExtendedPointerFormatException> {
            SqsExtendedPointerCodec.decode("not-a-pointer", queueUrl, policyFingerprint, key)
        }
    }

    @Test
    fun `pointer codec rejects non canonical base64url segments`() {
        val key = ByteArray(32) { it.toByte() }
        val queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789012/${Base58.randomString(16)}"
        val policyFingerprint = "fingerprint-${Base58.randomString(16)}"
        val encoded = SqsExtendedPointerCodec.encode(
            bucket = "bucket-${Base58.randomString(16)}",
            key = "payload/${Base58.randomString(16)}",
            contentType = null,
            encrypted = false,
            queueUrl = queueUrl,
            policyFingerprint = policyFingerprint,
            signingKey = key,
        )
        val parts = encoded.split('.')

        assertFailsWith<SqsExtendedPointerFormatException> {
            SqsExtendedPointerCodec.decode(
                "${parts[0]}.${parts[1]}=.${parts[2]}",
                queueUrl,
                policyFingerprint,
                key,
            )
        }
        assertFailsWith<SqsExtendedPointerFormatException> {
            SqsExtendedPointerCodec.decode(
                "${parts[0]}.${parts[1]}.${parts[2]}=",
                queueUrl,
                policyFingerprint,
                key,
            )
        }
    }
}
