package io.bluetape4k.aws.examples.ktor.exposed

import io.bluetape4k.aws.ktor.exposed.AwsExposedPlugin
import io.bluetape4k.aws.ktor.exposed.awsExposedTransaction
import io.bluetape4k.support.requireNotBlank
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import java.io.Serializable

/**
 * JDBC settings used by the Ktor Exposed example module.
 */
data class ExampleDatabaseConfig(
    val url: String,
    val driverClassName: String,
    val username: String,
    val password: String,
    val maximumPoolSize: Int = 2,
    val minimumIdle: Int = 0,
): Serializable {

    init {
        url.requireNotBlank("url")
        driverClassName.requireNotBlank("driverClassName")
        username.requireNotBlank("username")
    }

    companion object {
        private const val serialVersionUID: Long = 5727245292370095932L
    }
}

/**
 * Installs JSON, the AWS Exposed plugin, schema setup, and example order routes.
 */
fun Application.exposedExampleModule(database: ExampleDatabaseConfig) {
    install(ContentNegotiation) {
        jackson()
    }

    install(AwsExposedPlugin) {
        defaultDatabase {
            url = database.url
            driverClassName = database.driverClassName
            username = database.username
            password = database.password
            pool {
                maximumPoolSize = database.maximumPoolSize
                minimumIdle = database.minimumIdle
            }
        }
    }

    monitor.subscribe(ApplicationStarted) {
        // Ktor startup events are synchronous; this mirrors AwsExposedPlugin's bounded startup bridge.
        runBlocking(Dispatchers.IO) {
            awsExposedTransaction {
                SchemaUtils.create(OrdersTable)
            }
        }
    }

    routing {
        route("/exposed/orders") {
            post {
                val request = call.receive<OrderRequest>()
                val created = call.awsExposedTransaction {
                    OrderRepository.save(request.toRecord())
                }
                call.respond(io.ktor.http.HttpStatusCode.Created, created)
            }

            get("/{id}") {
                val id = call.parameters["id"]?.toLongOrNull()
                if (id == null) {
                    call.respond(io.ktor.http.HttpStatusCode.BadRequest, "Invalid order id.")
                    return@get
                }

                val found = call.awsExposedTransaction {
                    OrderRepository.findByIdOrNull(id)
                }
                if (found == null) {
                    call.respond(io.ktor.http.HttpStatusCode.NotFound)
                } else {
                    call.respond(found)
                }
            }

            get {
                val customerId = call.request.queryParameters["customerId"]
                val orders = call.awsExposedTransaction {
                    if (customerId.isNullOrBlank()) {
                        OrderRepository.findAll()
                    } else {
                        OrderRepository.findByCustomerId(customerId)
                    }
                }
                call.respond(orders)
            }
        }
    }
}

private fun OrderRequest.toRecord(): OrderRecord =
    OrderRecord(
        customerId = customerId,
        status = status,
        notes = notes,
    )
