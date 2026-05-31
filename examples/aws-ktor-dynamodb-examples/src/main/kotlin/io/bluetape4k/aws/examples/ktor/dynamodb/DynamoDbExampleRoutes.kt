package io.bluetape4k.aws.examples.ktor.dynamodb

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.BillingMode
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.aws.kotlin.dynamodb.DynamoItemMapper
import io.bluetape4k.aws.kotlin.dynamodb.DynamoItemReader
import io.bluetape4k.aws.kotlin.dynamodb.model.partitionKeyOf
import io.bluetape4k.aws.kotlin.dynamodb.model.stringAttrDefinitionOf
import io.bluetape4k.aws.ktor.dynamodb.DynamoDbKtorPlugin
import io.bluetape4k.aws.ktor.dynamodb.dynamoDb
import io.bluetape4k.ktor.core.requiredPathParameter
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.toList
import java.io.Serializable

data class Order(
    val id: String,
    val status: String,
    val description: String = "",
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

private const val ORDERS_TABLE = "orders"

private val orderMapper = DynamoItemMapper<Order> { order ->
    mapOf(
        "id" to AttributeValue.S(order.id),
        "status" to AttributeValue.S(order.status),
        "description" to AttributeValue.S(order.description),
    )
}

private val orderReader = DynamoItemReader<Order> { item ->
    Order(
        id = item.getValue("id").asS(),
        status = item.getValue("status").asS(),
        description = item["description"]?.asS() ?: "",
    )
}

private val orderKeyMapper = DynamoItemMapper<String> { id ->
    mapOf("id" to AttributeValue.S(id))
}

fun Application.dynamoDbExampleModule(
    endpointUrl: Url,
    region: String,
    credentialsProvider: CredentialsProvider,
) {
    install(ContentNegotiation) { jackson() }

    install(DynamoDbKtorPlugin) {
        this.endpointUrl = endpointUrl
        this.region = region
        this.credentialsProvider = credentialsProvider
        autoCreateTables = true
        table(
            tableName = ORDERS_TABLE,
            keySchema = listOf(partitionKeyOf("id")),
            attributeDefinitions = listOf(stringAttrDefinitionOf("id")),
        ) {
            billingMode = BillingMode.PayPerRequest
        }
    }

    routing {
        post("/dynamodb/orders") {
            val order = call.receive<Order>()
            if (order.id.isBlank() || order.status.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, "id and status must not be blank")
                return@post
            }
            val repo = call.application.dynamoDb().repository(ORDERS_TABLE, orderMapper, orderReader, orderKeyMapper)
            val saved = repo.save(order)
            call.respond(HttpStatusCode.Created, saved)
        }

        get("/dynamodb/orders/{id}") {
            val id = call.requiredPathParameter("id")
            val repo = call.application.dynamoDb().repository(ORDERS_TABLE, orderMapper, orderReader, orderKeyMapper)
            val order = repo.findById(id)
            if (order != null) {
                call.respond(order)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        delete("/dynamodb/orders/{id}") {
            val id = call.requiredPathParameter("id")
            val repo = call.application.dynamoDb().repository(ORDERS_TABLE, orderMapper, orderReader, orderKeyMapper)
            repo.deleteById(id)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/dynamodb/orders") {
            val repo = call.application.dynamoDb().repository(ORDERS_TABLE, orderMapper, orderReader, orderKeyMapper)
            val orders = repo.scan().toList()
            call.respond(orders)
        }
    }
}
