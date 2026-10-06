package io.bluetape4k.aws.kotlin.dynamodb.coordination

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.io.serializer.BinarySerializers
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test

@Suppress("UnusedDataClassCopyResult")
class LockLeaseTest {

    companion object: KLogging()

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

        log.debug { "lease=$lease" }

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
        val bytes = BinarySerializers.Jdk.serialize(lease)
        val restored = BinarySerializers.Jdk.deserialize<LockLease>(bytes)

        log.debug { "restored=$restored" }
        restored shouldBeEqualTo lease
    }

    @Test
    fun `LockLease fastFory serialization invariant를 다시 검증한다`() {
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
        val bytes = BinarySerializers.FastFory.serialize(lease)
        val restored = BinarySerializers.FastFory.deserialize<LockLease>(bytes)

        log.debug { "restored=$restored" }
        restored shouldBeEqualTo lease
    }
}
