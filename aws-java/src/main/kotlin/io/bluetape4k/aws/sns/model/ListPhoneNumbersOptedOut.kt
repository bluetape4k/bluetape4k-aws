package io.bluetape4k.aws.sns.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.services.sns.model.ListPhoneNumbersOptedOutRequest

/**
 * Builds a [ListPhoneNumbersOptedOutRequest] with a DSL block.
 *
 * ## Behavior/Contract
 * - Sets `nextToken` and other fields directly in the [builder] block.
 *
 * ```kotlin
 * val req = listPhoneNumbersOptedOutRequest { nextToken("token123") }
 * ```
 */
inline fun listPhoneNumbersOptedOutRequest(
    builder: ListPhoneNumbersOptedOutRequest.Builder.() -> Unit,
): ListPhoneNumbersOptedOutRequest =
    ListPhoneNumbersOptedOutRequest.builder().apply(builder).build()

/**
 * Creates a [ListPhoneNumbersOptedOutRequest] for listing SMS opted-out phone numbers by pagination token.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [nextToken] is non-null and blank.
 * - When [nextToken] is null, retrieves the first page.
 *
 * ```kotlin
 * val req = listPhoneNumbersOptedOutRequestOf()
 * // Request the first page of opted-out phone numbers
 * ```
 */
inline fun listPhoneNumbersOptedOutRequestOf(
    nextToken: String? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: ListPhoneNumbersOptedOutRequest.Builder.() -> Unit = {},
): ListPhoneNumbersOptedOutRequest =
    listPhoneNumbersOptedOutRequest {
        nextToken?.let {
            nextToken.requireNotBlank("nextToken")
            nextToken(it)
        }
        overrideConfiguration?.let { overrideConfiguration(it) }

        builder()
    }
