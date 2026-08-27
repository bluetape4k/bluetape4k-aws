package io.bluetape4k.aws.kotlin.dynamodb.coordination

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import org.junit.jupiter.api.Test

class LockLeaseTest {

    @Test
    fun `LockLease는 positive token과 epoch expiry 및 scope를 검증한다`() {
        val lease = LockLease(
            key = "orders",
            ownerId = "worker-1",
            fencingToken = 1L,
            expiresAtEpochSeconds = 1_756_195_200L,
            tableName = "coordination",
            partitionKeyAttributeName = "id",
            namespace = "default",
            physicalKey = "6:default4:LOCK6:orders",
            scopeId = "scope",
        )

        lease.fencingToken shouldBeEqualTo 1L
        lease.expiresAtEpochSeconds shouldBeEqualTo 1_756_195_200L

        assertFailsWith<IllegalArgumentException> {
            lease.copy(fencingToken = 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            lease.copy(expiresAtEpochSeconds = -1L)
        }
    }

    @Test
    fun `LockLease serialization readObject도 invariant를 다시 검증한다`() {
        val lease = LockLease(
            key = "orders",
            ownerId = "worker-1",
            fencingToken = 2L,
            expiresAtEpochSeconds = 1_756_195_200L,
            tableName = "coordination",
            partitionKeyAttributeName = "id",
            namespace = "default",
            physicalKey = "6:default4:LOCK6:orders",
            scopeId = "scope",
        )
        val bytes = ByteArrayOutputStream().also { output ->
            ObjectOutputStream(output).use { it.writeObject(lease) }
        }.toByteArray()

        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as LockLease }
        restored shouldBeEqualTo lease
    }
}
