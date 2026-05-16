package io.bluetape4k.aws.examples.spring.dynamodb

import io.bluetape4k.aws.spring.dynamodb.AbstractCoroutinesDynamoDbRepository
import io.bluetape4k.aws.spring.dynamodb.DynamoDbTableNameResolver
import org.springframework.stereotype.Repository
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient
import software.amazon.awssdk.enhanced.dynamodb.Key
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey
import java.io.Serializable

@DynamoDbBean
class Order : Serializable {
    @get:DynamoDbPartitionKey
    var id: String = ""
    var status: String = ""
    var description: String = ""

    constructor()

    constructor(id: String, status: String, description: String = "") {
        this.id = id
        this.status = status
        this.description = description
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

@Repository
class OrderRepository(
    enhancedClient: DynamoDbEnhancedAsyncClient,
    tableNameResolver: DynamoDbTableNameResolver,
) : AbstractCoroutinesDynamoDbRepository<Order, String>(
    enhancedClient = enhancedClient,
    tableNameResolver = tableNameResolver,
    entityClass = Order::class.java,
) {
    override val tableName: String = "orders"

    override fun keyFromId(id: String): Key =
        Key.builder().partitionValue(id).build()

    override fun keyFromItem(item: Order): Key =
        Key.builder().partitionValue(item.id).build()
}
