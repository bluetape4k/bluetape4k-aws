package io.bluetape4k.aws.examples.ktor.exposed

import io.bluetape4k.aws.ktor.exposed.AwsExposedPlugin
import io.bluetape4k.aws.ktor.exposed.awsExposedTransaction
import io.bluetape4k.ktor.core.requiredPathParameter
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
 * Ktor Exposed 예제 모듈에서 사용하는 JDBC 설정입니다.
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
 * JSON, AWS Exposed plugin, 스키마 초기화와 주문 예제 route를 등록합니다.
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
        // Ktor startup event는 동기식이므로 AwsExposedPlugin의 제한된 startup bridge를 따릅니다.
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
                val id = call.requiredPathParameter("id").toLongOrNull()
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
                val request = try {
                    OrderPageRequest.parse(
                        rawCursor = call.request.queryParameters["cursor"],
                        rawLimit = call.request.queryParameters["limit"],
                        customerId = call.request.queryParameters["customerId"],
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        io.ktor.http.HttpStatusCode.BadRequest,
                        e.message ?: "Invalid order page request.",
                    )
                    return@get
                }
                val page = call.awsExposedTransaction {
                    OrderRepository.findOrderPage(request)
                }
                call.respond(page)
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
