package io.bluetape4k.aws.spring.connection

import org.springframework.boot.testcontainers.service.connection.ContainerConnectionDetailsFactory
import org.springframework.boot.testcontainers.service.connection.ContainerConnectionSource
import org.testcontainers.containers.Container

internal class KinesisContainerConnectionDetailsFactory :
    ContainerConnectionDetailsFactory<Container<*>, KinesisConnectionDetails>(
        "kinesis",
        "software.amazon.awssdk.services.kinesis.KinesisClient",
) {
    override fun sourceAccepts(
        source: ContainerConnectionSource<Container<*>>,
        requiredContainerType: Class<*>,
        requiredConnectionDetailsType: Class<*>,
    ): Boolean =
        super.sourceAccepts(source, requiredContainerType, requiredConnectionDetailsType) ||
            source.accepts(null, requiredContainerType, requiredConnectionDetailsType)

    override fun getContainerConnectionDetails(
        source: ContainerConnectionSource<Container<*>>,
    ): KinesisConnectionDetails? {
        val container = resolveContainer(source, "kinesis")
        return if (isSupportedAwsEmulator(container)) KinesisContainerConnectionDetails(source) else null
    }

    private class KinesisContainerConnectionDetails(
        source: ContainerConnectionSource<Container<*>>,
    ): ContainerConnectionDetailsFactory.ContainerConnectionDetails<Container<*>>(source), KinesisConnectionDetails {
        private var values: AwsServiceConnectionValues? = null

        override fun afterPropertiesSet() {
            values = initializeAwsServiceConnectionDetails(
                serviceName = "kinesis",
                initialize = { super.afterPropertiesSet() },
                snapshot = { snapshotAwsServiceConnection(getContainer(), "kinesis") },
            )
        }

        override val endpoint get() = requireValues().endpoint
        override val region get() = requireValues().region
        override val accessKey get() = requireValues().accessKey
        override val secretKey get() = requireValues().secretKey

        override fun toString(): String = values?.toString()
            ?: "KinesisConnectionDetails(endpoint=[UNINITIALIZED], region=[UNINITIALIZED], " +
            "accessKey=[REDACTED], secretKey=[REDACTED])"

        private fun requireValues(): AwsServiceConnectionValues =
            checkNotNull(values) { "Kinesis connection details have not been initialized" }
    }
}
