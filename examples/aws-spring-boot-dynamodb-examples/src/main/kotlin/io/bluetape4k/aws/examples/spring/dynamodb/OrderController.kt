package io.bluetape4k.aws.examples.spring.dynamodb

import kotlinx.coroutines.flow.Flow
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/orders")
class OrderController(private val repository: OrderRepository) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun save(@RequestBody request: OrderRequest): Order {
        val order = Order(
            id = UUID.randomUUID().toString(),
            status = request.status,
            description = request.description,
        )
        return repository.save(order)
    }

    @GetMapping("/{id}")
    suspend fun findById(@PathVariable id: String): Order =
        repository.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found: $id")

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun deleteById(@PathVariable id: String) {
        repository.deleteById(id)
    }

    @GetMapping
    fun findAll(): Flow<Order> = repository.scan()
}

data class OrderRequest(
    val status: String = "NEW",
    val description: String = "",
)
