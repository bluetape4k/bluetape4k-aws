package io.bluetape4k.aws.spring.sqs

import org.springframework.context.SmartLifecycle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 애플리케이션의 SQS 리스너 컨테이너를 수명주기 단위로 관리하는 레지스트리.
 */
class SqsMessageListenerContainerRegistry: SmartLifecycle {

    private val running = AtomicBoolean(false)
    private val containerMap = ConcurrentHashMap<String, SqsMessageListenerContainer>()

    val containers: Collection<SqsMessageListenerContainer>
        get() = containerMap.values

    internal fun register(id: String, container: SqsMessageListenerContainer) {
        require(containerMap.putIfAbsent(id, container) == null) {
            "Duplicate SQS listener id: $id"
        }
        if (running.get() && container.isAutoStartup) {
            container.start()
        }
    }

    internal fun getContainer(id: String): SqsMessageListenerContainer? =
        containerMap[id]

    override fun start() {
        if (running.compareAndSet(false, true)) {
            containers.filter { it.isAutoStartup }.forEach { it.start() }
        }
    }

    override fun stop() {
        stop(Runnable {})
    }

    override fun stop(callback: Runnable) {
        if (!running.compareAndSet(true, false)) {
            callback.run()
            return
        }
        val currentContainers = containers.toList()
        if (currentContainers.isEmpty()) {
            callback.run()
            return
        }
        val remaining = AtomicInteger(currentContainers.size)
        currentContainers.forEach {
            it.stop {
                if (remaining.decrementAndGet() == 0) {
                    callback.run()
                }
            }
        }
    }

    override fun isRunning(): Boolean = running.get()

    override fun getPhase(): Int =
        containers.maxOfOrNull { it.phase } ?: Int.MAX_VALUE
}
