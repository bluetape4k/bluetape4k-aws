package io.bluetape4k.aws.dynamodb.examples.food.tests

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeLessThan
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldContainAll
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.aws.dynamodb.examples.food.AbstractFoodApplicationTest
import io.bluetape4k.aws.dynamodb.examples.food.model.FoodDocument
import io.bluetape4k.aws.dynamodb.examples.food.model.FoodState
import io.bluetape4k.aws.dynamodb.examples.food.repository.FoodRepository
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.junit5.coroutines.runSuspendTest
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.uninitialized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.all
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import kotlin.random.Random

class FoodRepositoryTest: AbstractFoodApplicationTest() {

    companion object: KLoggingChannel() {
        private fun createFoods(size: Int = 100): List<FoodDocument> {
            size.requirePositiveNumber("size")

            return List(size) {
                FoodDocument(
                    id = Uuid.V7.nextIdAsString(),
                    restraurantId = Random.nextInt(5).toString(),
                    state = FoodState.entries.random(),
                    updatedAt = Instant.now()
                )
            }
        }
    }

    @Autowired
    private val repository: FoodRepository = uninitialized()

    @Test
    fun `save one food and load`() = runSuspendIO {
        val food = FoodDocument(
            Uuid.V7.nextIdAsString(),
            "42",
            FoodState.entries.random(),
            Instant.now().minusSeconds(60_000L)
        )
        log.info { "Save food. $food" }
        repository.save(food)

        yield()

        val loadedFoods = repository.findByPartitionKey(
            food.partitionKey,
            Instant.now().minusSeconds(90_000L),
            Instant.now()
        )
        loadedFoods.forEach {
            log.debug { "loaded food=$it" }
        }
        loadedFoods shouldContain food
        loadedFoods.size shouldBeGreaterThan 0
    }

    @Test
    fun `batch write`() = runSuspendIO {
        val updatedAt = Instant.now()

        // DynamoDB Batch Write의 최대 크기가 25 임
        val foods = createFoods(100)
        val result = repository.saveAll(foods)
        result.all { it.unprocessedPutItemsForTable(repository.table).isEmpty() }.shouldBeTrue()

        // 100 개중 대략 1/5 인 partitionKey 에 해당하는 food 만 조회한다.
        val food = foods.random()
        val loadedFoods = repository.findByPartitionKey(
            food.partitionKey,
            updatedAt,
            Instant.now()
        )
        log.debug { "loaded foods: ${loadedFoods.size}" }
        loadedFoods.shouldNotBeEmpty()
        loadedFoods.all { it.partitionKey == food.partitionKey }.shouldBeTrue()
        foods shouldContainAll loadedFoods
        loadedFoods.size shouldBeLessThan foods.size
    }

    @Test
    fun `delete item`() = runSuspendIO {
        val food = createFoods(1).first()
        repository.save(food)
        log.debug { "saved food=$food" }

        val deleted = repository.delete(food)
        log.debug { "deleted food=$deleted" }
        deleted.shouldNotBeNull() shouldBeEqualTo food
    }

    @Test
    fun `delete all items`() = runSuspendTest(Dispatchers.IO) {
        val updatedAt = Instant.now()
        val foods = createFoods(100)
        val result = repository.saveAll(foods)
        result.all { it.unprocessedPutItemsForTable(repository.table).isEmpty() }.shouldBeTrue()

        // 모든 foods 삭제
        repository.deleteAll(foods).toList() shouldHaveSize foods.size

        // 모두 삭제되었으므로 empty
        val food = foods.random()
        val loadedFoods = repository.findByPartitionKey(
            food.partitionKey,
            updatedAt,
            Instant.now()
        )
        loadedFoods.shouldBeEmpty()
    }

    @Test
    fun `delete all items by key`() = runSuspendIO {
        val updatedAt = Instant.now()
        val foods = createFoods(100)

        // 100 개 food 생성
        val result = repository.saveAll(foods)
        result.all { it.unprocessedPutItemsForTable(repository.table).isEmpty() }.shouldBeTrue()

        // 해당 Key 의 food 들을 모두 삭제한다.
        val keysToDelete = foods.map { it.key }
        log.debug { "deleted food size=${keysToDelete.size}" }
        repository.deleteAllByKeys(keysToDelete).toList() shouldHaveSize keysToDelete.size

        val food = foods.random()
        val loadedFoods = repository.findByPartitionKey(
            food.partitionKey,
            updatedAt,
            Instant.now()
        )
        loadedFoods.shouldBeEmpty()
    }
}
