package io.bluetape4k.aws.kotlin.s3.model

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TestBucketsTest {
    private val buckets = listOf("source", "destination")

    @Test
    fun `두 번째 생성 실패 시 생성한 버킷만 정리한다`() = runTest {
        val deleted = mutableListOf<String>()
        val failure = IllegalStateException("create")
        val actual = assertFailsWith<IllegalStateException> {
            withTestBuckets(
                buckets,
                create = { if (it == "destination") throw failure },
                delete = { deleted.add(it) },
            ) {
                error("본문은 실행되면 안 됩니다")
            }
        }
        actual shouldBeEqualTo failure
        deleted shouldBeEqualTo listOf("source")
    }

    @Test
    fun `정상 완료 시 생성 역순으로 정리하고 결과를 반환한다`() = runTest {
        val deleted = mutableListOf<String>()
        val result = withTestBuckets(buckets, create = {}, delete = { deleted.add(it) }) { "result" }
        result shouldBeEqualTo "result"
        deleted shouldBeEqualTo buckets.asReversed()
    }

    @Test
    fun `본문과 정리 실패 시 원래 실패를 보존하고 나머지도 정리한다`() = runTest {
        val deleted = mutableListOf<String>()
        val failure = IllegalStateException("body")
        val cleanup = IllegalArgumentException("delete")
        val actual = assertFailsWith<IllegalStateException> {
            withTestBuckets(buckets, create = {}, delete = {
                deleted.add(it)
                if (it == "destination") throw cleanup
            }) { throw failure }
        }
        actual shouldBeEqualTo failure
        actual.suppressed.toList() shouldBeEqualTo listOf(cleanup)
        deleted shouldBeEqualTo buckets.asReversed()
    }

    @Test
    fun `정리만 실패해도 나머지 버킷 정리를 시도하고 실패를 전달한다`() = runTest {
        val deleted = mutableListOf<String>()
        val failure = IllegalStateException("delete")
        val actual = assertFailsWith<IllegalStateException> {
            withTestBuckets(buckets, create = {}, delete = {
                deleted.add(it)
                throw failure
            }) { Unit }
        }
        actual shouldBeEqualTo failure
        deleted shouldBeEqualTo buckets.asReversed()
    }

    @Test
    fun `서로 다른 정리 실패도 모두 보존한다`() = runTest {
        val first = IllegalStateException("destination delete")
        val second = IllegalArgumentException("source delete")
        val actual = assertFailsWith<IllegalStateException> {
            withTestBuckets(buckets, create = {}, delete = {
                if (it == "destination") throw first else throw second
            }) { Unit }
        }
        actual shouldBeEqualTo first
        actual.suppressed.toList() shouldBeEqualTo listOf(second)
    }

    @Test
    fun `실제 작업 취소 후에도 중단 가능한 정리를 끝낸다`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val original = CompletableDeferred<CancellationException>()
        val propagated = CompletableDeferred<CancellationException>()
        val deleted = mutableListOf<String>()
        val job = launch {
            try {
                withTestBuckets(buckets, create = {}, delete = {
                    delay(1)
                    deleted.add(it)
                }) {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } catch (cancelled: CancellationException) {
                        original.complete(cancelled)
                        throw cancelled
                    }
                }
            } catch (cancelled: CancellationException) {
                propagated.complete(cancelled)
                throw cancelled
            }
        }
        entered.await()
        job.cancelAndJoin()
        job.isCancelled shouldBeEqualTo true
        propagated.isCompleted shouldBeEqualTo true
        propagated.await() shouldBeEqualTo original.await()
        deleted shouldBeEqualTo buckets.asReversed()
    }
}
