package io.bluetape4k.aws.dynamodb.query

import java.io.Serializable

/**
 * Class that supports `PrimaryKey` in the DSL.
 *
 * [keyName] is used as the key in `QueryRequest.keyConditions`.
 *
 * ```kotlin
 * val pk = PrimaryKey(keyName = "userId", equals = Equals("user-1"))
 * // pk.keyName == "userId"
 * ```
 */
@DynamoDslMarker
data class PrimaryKey(val keyName: String = "primaryKey", val equals: Equals): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Builder class for creating a [PrimaryKey].
 *
 * ```kotlin
 * val builder = PrimaryKeyBuilder("userId")
 * builder eq "user-1"
 * val pk = builder.build()
 * // pk.keyName == "userId"
 * ```
 */
@DynamoDslMarker
class PrimaryKeyBuilder(val keyName: String = "primaryKey") {
    var comparator: Equals? = null

    /** Creates a [PrimaryKey] from the configured comparator. */
    fun build(): PrimaryKey {
        // WHY: provide a clear error when build() is called without eq(), instead of using a non-null assertion.
        val cmp = checkNotNull(comparator) { "PrimaryKeyBuilder: comparator must be set via 'eq' before build()" }
        return PrimaryKey(keyName, cmp)
    }
}

/** Sets the partition-key comparison expression to `EQ`. */
infix fun PrimaryKeyBuilder.eq(value: Any) {
    comparator = Equals(value)
}
