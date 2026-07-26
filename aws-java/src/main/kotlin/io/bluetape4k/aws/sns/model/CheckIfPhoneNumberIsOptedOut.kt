package io.bluetape4k.aws.sns.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.sns.model.CheckIfPhoneNumberIsOptedOutRequest

/**
 * Builds a [CheckIfPhoneNumberIsOptedOutRequest] with a DSL block.
 *
 * ## Behavior/Contract
 * - Sets `phoneNumber` and other fields directly in the [builder] block.
 *
 * ```kotlin
 * val req = checkIfPhoneNumberIsOptedOutRequest {
 *     phoneNumber("+821012345678")
 * }
 * ```
 */
inline fun checkIfPhoneNumberIsOptedOutRequest(
    builder: CheckIfPhoneNumberIsOptedOutRequest.Builder.() -> Unit,
): CheckIfPhoneNumberIsOptedOutRequest =
    CheckIfPhoneNumberIsOptedOutRequest.builder().apply(builder).build()

/**
 * Creates a [CheckIfPhoneNumberIsOptedOutRequest] that checks whether SMS is opted out for a phone number.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [phoneNumber] is blank.
 *
 * ```kotlin
 * val req = checkIfPhoneNumberIsOptedOutRequestOf("+821012345678")
 * // req.phoneNumber() == "+821012345678"
 * ```
 */
inline fun checkIfPhoneNumberIsOptedOutRequestOf(
    phoneNumber: String,
    bulider: CheckIfPhoneNumberIsOptedOutRequest.Builder.() -> Unit = {},
): CheckIfPhoneNumberIsOptedOutRequest {
    phoneNumber.requireNotBlank("phoneNumber")

    return checkIfPhoneNumberIsOptedOutRequest {
        phoneNumber(phoneNumber)
        bulider()
    }
}
