package io.bluetape4k.aws.dynamodb.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.reactive.asFlow
import software.amazon.awssdk.core.async.SdkPublisher
import software.amazon.awssdk.enhanced.dynamodb.model.Page
import software.amazon.awssdk.enhanced.dynamodb.model.PagePublisher

/**
 * See the API documentation for details.
 *
 * ```kotlin
 * val items = publisher.findFirst()
 * // items.isNotEmpty() == true
 * ```
 */
suspend fun <T: Any> SdkPublisher<Page<T>>.findFirst(): List<T> =
    asFlow().firstOrNull()?.items() ?: emptyList()

/**
 * See the API documentation for details.
 *
 * ```kotlin
 * val items = pagePublisher.findFirst()
 * // items.isNotEmpty() == true
 * ```
 */
suspend fun <T: Any> PagePublisher<T>.findFirst(): List<T> =
    asFlow().firstOrNull()?.items() ?: emptyList()

/**
 * See the API documentation for details.
 *
 * ```kotlin
 * val count = publisher.count()
 * // count >= 0L
 * ```
 */
suspend fun <T: Any> SdkPublisher<Page<T>>.count(): Long =
    asFlow().first().items().count().toLong()

/**
 * See the API documentation for details.
 *
 * ```kotlin
 * val count = pagePublisher.count()
 * // count >= 0L
 * ```
 */
suspend fun <T: Any> PagePublisher<T>.count(): Long =
    asFlow().first().items().count().toLong()
