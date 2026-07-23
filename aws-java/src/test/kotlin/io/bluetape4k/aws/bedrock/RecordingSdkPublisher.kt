package io.bluetape4k.aws.bedrock

import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import software.amazon.awssdk.core.async.SdkPublisher

internal class RecordingSdkPublisher<T>(
    private val onSubscribed: () -> Unit = {},
    private val onCancelled: () -> Unit = {},
) : SdkPublisher<T> {

    private val lock = Any()
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
        synchronized(lock) {
            check(this.subscriber == null) { "RecordingSdkPublisher supports one subscriber" }
            this.subscriber = subscriber
        }
        subscriber.onSubscribe(
            object : Subscription {
                override fun request(n: Long) {
                    if (n <= 0) {
                        fail(IllegalArgumentException("Reactive Streams demand must be positive"))
                        return
                    }
                    synchronized(lock) {
                        if (cancelled || terminal) return
                        requests += n
                        outstanding += n
                        maxOutstanding = maxOf(maxOutstanding, outstanding)
                    }
                }

                override fun cancel() {
                    val notifyCancelled = synchronized(lock) {
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
        val target = synchronized(lock) {
            if (cancelled || terminal || outstanding == 0L) return false
            outstanding--
            emitted += value
            subscriber
        }
        target?.onNext(value)
        return true
    }

    fun complete() {
        val target = synchronized(lock) {
            if (cancelled || terminal) return
            terminal = true
            terminalCount++
            subscriber
        }
        target?.onComplete()
    }

    fun fail(cause: Throwable) {
        val target = synchronized(lock) {
            if (cancelled || terminal) return
            terminal = true
            terminalCount++
            subscriber
        }
        target?.onError(cause)
    }

    fun adversarialNext(value: T) {
        synchronized(lock) { subscriber }?.onNext(value)
    }

    fun adversarialError(cause: Throwable) {
        synchronized(lock) { subscriber }?.onError(cause)
    }

    fun adversarialComplete() {
        synchronized(lock) { subscriber }?.onComplete()
    }
}
