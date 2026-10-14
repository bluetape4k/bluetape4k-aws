package io.bluetape4k.aws.bedrock

import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import software.amazon.awssdk.core.async.SdkPublisher
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class RecordingSdkPublisher<T>(
    private val onSubscribed: () -> Unit = {},
    private val onCancelled: () -> Unit = {},
): SdkPublisher<T> {

    private val lock = ReentrantLock()
    private var subscriber: Subscriber<in T>? = null
    private var cancelled = false
    private var terminal = false

    val requests = mutableListOf<Long>()
    val emitted = mutableListOf<T>()
    var outstanding = 0L
        private set
    var maxOutstanding = 0L
        private set
    var cancelCount = 0
        private set
    var terminalCount = 0
        private set

    override fun subscribe(subscriber: Subscriber<in T>) {
        lock.withLock {
            check(this.subscriber == null) { "RecordingSdkPublisher supports one subscriber" }
            this.subscriber = subscriber
        }
        subscriber.onSubscribe(
            object: Subscription {
                override fun request(n: Long) {
                    if (n <= 0) {
                        fail(IllegalArgumentException("Reactive Streams demand must be positive"))
                        return
                    }
                    lock.withLock {
                        if (cancelled || terminal) return
                        requests += n
                        outstanding += n
                        maxOutstanding = maxOf(maxOutstanding, outstanding)
                    }
                }

                override fun cancel() {
                    val notifyCancelled = lock.withLock {
                        if (!cancelled) {
                            cancelled = true
                            cancelCount++
                            true
                        } else {
                            false
                        }
                    }
                    if (notifyCancelled) onCancelled()
                }
            },
        )
        onSubscribed()
    }

    fun emitOne(value: T): Boolean {
        val target = lock.withLock {
            if (cancelled || terminal || outstanding == 0L) return false
            outstanding--
            emitted += value
            subscriber
        }
        target?.onNext(value)
        return true
    }

    fun complete() {
        val target = lock.withLock {
            if (cancelled || terminal) return
            terminal = true
            terminalCount++
            subscriber
        }
        target?.onComplete()
    }

    fun fail(cause: Throwable) {
        val target = lock.withLock {
            if (cancelled || terminal) return
            terminal = true
            terminalCount++
            subscriber
        }
        target?.onError(cause)
    }

    fun adversarialNext(value: T) {
        lock.withLock { subscriber }?.onNext(value)
    }

    fun adversarialError(cause: Throwable) {
        lock.withLock { subscriber }?.onError(cause)
    }

    fun adversarialComplete() {
        lock.withLock { subscriber }?.onComplete()
    }
}
