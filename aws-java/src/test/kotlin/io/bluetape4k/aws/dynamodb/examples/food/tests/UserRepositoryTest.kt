package io.bluetape4k.aws.dynamodb.examples.food.tests

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeLessThan
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.aws.dynamodb.examples.food.AbstractFoodApplicationTest
import io.bluetape4k.aws.dynamodb.examples.food.model.UserDocument
import io.bluetape4k.aws.dynamodb.examples.food.repository.UserRepository
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.support.uninitialized
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.random.Random

class UserRepositoryTest: AbstractFoodApplicationTest() {
    companion object: KLoggingChannel() {
        private fun createUser(): UserDocument {
            return UserDocument(
                serviceId = "matrix" + Random.nextInt(5),
                userId = Uuid.V7.nextIdAsString(),
                status = UserDocument.UserStatus.entries.random()
            )
        }
    }

    @Autowired
    private val repository: UserRepository = uninitialized()

    @Test
    fun `save item and load`() = runSuspendIO {
        val user = createUser()
        repository.save(user)

        val loaded = repository.findByKey(user.key).shouldNotBeNull()
        loaded shouldBeEqualTo user
    }

    @Test
    fun `save item and delete`() = runSuspendIO {
        val user = createUser()
        repository.save(user)

        val loaded = repository.findByKey(user.key).shouldNotBeNull()
        loaded shouldBeEqualTo user

        val deleted = repository.delete(user).shouldNotBeNull()
        deleted shouldBeEqualTo user
    }

    @Test
    fun `save item and update`() = runSuspendIO {
        val user = createUser()
        repository.save(user)

        val loaded = repository.findByKey(user.key).shouldNotBeNull()
        loaded shouldBeEqualTo user

        loaded.userStatus = UserDocument.UserStatus.INACTIVE
        val updated = repository.update(loaded).shouldNotBeNull()

        updated.userStatus shouldBeEqualTo UserDocument.UserStatus.INACTIVE
    }

    @Test
    fun `save many items`() = runSuspendIO {
        val users = List(100) { createUser() }

        val saved = repository.saveAll(users).toList()
        saved.all {
            it.unprocessedPutItemsForTable(repository.table).isEmpty()
        }.shouldBeTrue()

        val loaded = repository.findFirstByPartitionKey(users.random().partitionKey)
        log.debug { "loaded size=${loaded.size}" }
        loaded.shouldNotBeEmpty()
        loaded.size shouldBeLessThan users.size
    }
}
