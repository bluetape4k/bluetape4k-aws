package io.bluetape4k.aws.dynamodb.model

import io.bluetape4k.AbstractValueObject
import io.bluetape4k.ToStringBuilder
import io.bluetape4k.aws.dynamodb.model.DynamoDbEntity.Companion.ENTITY_ID_DELIMITER
import io.bluetape4k.aws.dynamodb.model.DynamoDbEntity.Companion.ENTITY_NAME_DELIMITER
import io.bluetape4k.idgenerators.snowflake.GlobalSnowflake
import io.bluetape4k.idgenerators.uuid.Uuid
import software.amazon.awssdk.enhanced.dynamodb.Key
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey
import java.io.Serializable

/**
 * See the API documentation for details.
 */
interface DynamoDbEntity: Serializable {
    companion object {
        const val ENTITY_ID_DELIMITER = "#"
        const val ENTITY_NAME_DELIMITER = ":"

        val snowflake by lazy { GlobalSnowflake() }
    }

    /**
     * See the API documentation for details.
     */
    @get:DynamoDbPartitionKey
    val partitionKey: String

    /**
     * See the API documentation for details.
     */
    @get:DynamoDbSortKey
    val sortKey: String

    /**
     * See the API documentation for details.
     */
    val key: Key

    /**
     * See the API documentation for details.
     */
    fun getUniqueLong(): Long = snowflake.nextId()

    /**
     * See the API documentation for details.
     */
    fun getUniqueUuidString(): String = Uuid.V7.nextBase62()
}

/**
 * See the API documentation for details.
 */
abstract class AbstractDynamoDbEntity:
    AbstractValueObject(),
    DynamoDbEntity {
    override val key: Key by lazy {
        Key
            .builder()
            .partitionValue(partitionKey)
            .sortValue(sortKey)
            .build()
    }

    override fun equalProperties(other: Any): Boolean =
        other is DynamoDbEntity &&
                partitionKey == other.partitionKey &&
                sortKey == other.sortKey

    override fun buildStringHelper(): ToStringBuilder =
        super
            .buildStringHelper()
            .add("partitionKey", partitionKey)
            .add("sortKey", sortKey)
}

/**
 * See the API documentation for details.
 *
 * See the API documentation for details.
 * See the API documentation for details.
 * See the API documentation for details.
 * See the API documentation for details.
 * See the API documentation for details.
 *
 * ```kotlin
 * val key = order.makeKeyString(partitionKey = "user1", sortKey = "order42")
 * // key == "Order:user1#order42"
 *
 * val keyNoSort = order.makeKeyString(partitionKey = "user1")
 * // keyNoSort == "Order:user1"
 * ```
 *
 * @param partitionKey Parameter.
 * @param sortKey Parameter.
 * @return Return value.
 */
inline fun <reified T: DynamoDbEntity> T.makeKeyString(
    partitionKey: Any? = null,
    sortKey: Any? = null,
): String =
    buildString {
        append(T::class.simpleName)

        partitionKey
            ?.takeIf { it.toString().isNotBlank() }
            ?.let {
                append(ENTITY_NAME_DELIMITER)
                append(it)
            }
        sortKey
            ?.takeIf { it.toString().isNotBlank() }
            ?.let {
                append(ENTITY_ID_DELIMITER)
                append(it)
            }
    }
