package io.bluetape4k.aws.dynamodb.schema

import io.bluetape4k.aws.dynamodb.model.provisionedThroughputOf
import io.bluetape4k.aws.exceptions.AwsBluetapeException
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.error
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import kotlinx.coroutines.future.await
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable
import software.amazon.awssdk.enhanced.dynamodb.model.CreateTableEnhancedRequest
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException
import kotlin.coroutines.cancellation.CancellationException

/**
 * See the API documentation for details.
 *
 * ```kotlin
 * val tableCreator = DynamoDbAsyncTableCreator()
 * val request = CreateTableEnhancedRequest {
 *    provisionedThroughput(tableCreator.defaultProvisionedThroughput)
 * }
 * tableCreator.tryCreateAsyncTable(asyncTable, request)
 * ```
 */
class DynamoDbAsyncTableCreator {

    companion object: KLoggingChannel() {
        const val DEFAULT_READ_CAPACITY_UNITS = 1L
        const val DEFAULT_WRITE_CAPACITY_UNITS = 1L
    }

    @JvmField
    val defaultProvisionedThroughput: ProvisionedThroughput =
        provisionedThroughputOf(DEFAULT_READ_CAPACITY_UNITS, DEFAULT_WRITE_CAPACITY_UNITS)

    /**
     * See the API documentation for details.
     * See the API documentation for details.
     *
     * ```kotlin
     * val tableCreator = DynamoDbAsyncTableCreator()
     * val request = CreateTableEnhancedRequest {
     *   provisionedThroughput(tableCreator.defaultProvisionedThroughput)
     * }
     * tableCreator.tryCreateAsyncTable(asyncTable, request)
     * ```
     *
     * @param asyncTable [DynamoDbAsyncTable] instance
     * @param request [CreateTableEnhancedRequest] instance
     */
    suspend fun <E: Any> tryCreateAsyncTable(
        asyncTable: DynamoDbAsyncTable<E>,
        request: CreateTableEnhancedRequest,
    ) {
        log.info { "Creating table ${asyncTable.tableName()}" }

        try {
            asyncTable.createTable(request).await()
            log.info { "Table [${asyncTable.tableName()}] created." }
        } catch (e: CancellationException) {
            // See the API documentation for details.
            throw e
        } catch (e: Throwable) {
            val causes = generateSequence(e) { it.cause }.toList()
            causes.filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }

            if (causes.any { it is ResourceInUseException }) {
                log.warn(e) { "Table [${asyncTable.tableName()}] already exists. Skipping creation." }
            } else {
                log.error(e) { "Fail to create table [${asyncTable.tableName()}]" }
                throw AwsBluetapeException("Fail to create table [${asyncTable.tableName()}]", e)
            }
        }
    }
}
