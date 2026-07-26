package io.bluetape4k.aws.core

import software.amazon.awssdk.core.SdkBytes
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset

/**
 * Converts a [ByteArray] into copy-based [SdkBytes].
 *
 * ## Behavior and contract
 * - Calls [SdkBytes.fromByteArray] and copies the input array.
 * - Later changes to the source array do not affect the created [SdkBytes].
 *
 * ```kotlin
 * val source = byteArrayOf(1, 2, 3)
 * val sdkBytes = source.toSdkBytes()
 * source[0] = 9
 * // sdkBytes.asByteArray()[0] == 1
 * ```
 */
fun ByteArray.toSdkBytes(): SdkBytes = SdkBytes.fromByteArray(this)

/**
 * Converts a [ByteArray] into zero-copy [SdkBytes].
 *
 * ## Behavior and contract
 * - Calls [SdkBytes.fromByteArrayUnsafe] and reuses the array reference.
 * - Mutating the source array may also change the bytes exposed by [SdkBytes].
 *
 * ```kotlin
 * val source = byteArrayOf(1, 2, 3)
 * val sdkBytes = source.toSdkBytesUnsafe()
 * source[0] = 9
 * // sdkBytes.asByteArrayUnsafe()[0] == 9
 * ```
 */
fun ByteArray.toSdkBytesUnsafe(): SdkBytes = SdkBytes.fromByteArrayUnsafe(this)

/**
 * Converts a string into [SdkBytes] encoded with the given charset.
 *
 * ## Behavior and contract
 * - The default charset is [Charsets.UTF_8].
 * - Returns the result of [SdkBytes.fromString] directly.
 *
 * ```kotlin
 * val sdkBytes = "hello".toSdkBytes()
 * // sdkBytes.asUtf8String() == "hello"
 * ```
 */
fun String.toSdkBytes(cs: Charset = Charsets.UTF_8): SdkBytes = SdkBytes.fromString(this, cs)

/**
 * Converts a string into UTF-8 [SdkBytes].
 *
 * ## Behavior and contract
 * - Uses [SdkBytes.fromUtf8String] to fix UTF-8 encoding.
 * - UTF-8 string deserialization returns the original value.
 *
 * ```kotlin
 * val sdkBytes = "hello".toUtf8SdkBytes()
 * // sdkBytes.asUtf8String() == "hello"
 * ```
 */
fun String.toUtf8SdkBytes(): SdkBytes = SdkBytes.fromUtf8String(this)

/**
 * Reads an entire [InputStream] and converts it into [SdkBytes].
 *
 * ## Behavior and contract
 * - Calls [SdkBytes.fromInputStream] and reads the stream to completion.
 * - The created [SdkBytes] contains a byte snapshot from the read time.
 *
 * ```kotlin
 * val input = "abc".byteInputStream()
 * val sdkBytes = input.toSdkBytes()
 * // sdkBytes.asUtf8String() == "abc"
 * ```
 */
fun InputStream.toSdkBytes(): SdkBytes = SdkBytes.fromInputStream(this)

/**
 * Creates [SdkBytes] from the current state of a [ByteBuffer].
 *
 * ## Behavior and contract
 * - Calls [SdkBytes.fromByteBuffer] and reads the buffer's `position..limit` range.
 * - The caller's `position/limit` settings before invocation determine the result.
 *
 * ```kotlin
 * val buffer = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)).apply { position(1) }
 * val sdkBytes = buffer.toSdkBytes()
 * // sdkBytes.asByteArray().contentEquals(byteArrayOf(2, 3, 4)) == true
 * ```
 */
fun ByteBuffer.toSdkBytes(): SdkBytes = SdkBytes.fromByteBuffer(this)
