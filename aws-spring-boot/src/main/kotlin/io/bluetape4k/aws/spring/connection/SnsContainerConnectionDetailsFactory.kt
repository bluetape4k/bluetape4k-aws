package io.bluetape4k.aws.spring.connection

import org.springframework.boot.testcontainers.service.connection.ContainerConnectionDetailsFactory
import org.springframework.boot.testcontainers.service.connection.ContainerConnectionSource
import org.testcontainers.containers.Container

internal class SnsContainerConnectionDetailsFactory :
    ContainerConnectionDetailsFactory<Container<*>, SnsConnectionDetails>(
        "sns",
        "software.amazon.awssdk.services.sns.SnsClient",
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
    ): SnsConnectionDetails? {
        val container = resolveContainer(source, "sns")
        return if (isSupportedAwsEmulator(container)) SnsContainerConnectionDetails(source) else null
    }

    private class SnsContainerConnectionDetails(
        source: ContainerConnectionSource<Container<*>>,
    ): ContainerConnectionDetailsFactory.ContainerConnectionDetails<Container<*>>(source), SnsConnectionDetails {
        private var values: AwsServiceConnectionValues? = null

        override fun afterPropertiesSet() {
            values = initializeAwsServiceConnectionDetails(
                serviceName = "sns",
                initialize = { super.afterPropertiesSet() },
                snapshot = { snapshotAwsServiceConnection(getContainer(), "sns") },
            )
        }

        override val endpoint get() = requireValues().endpoint
        override val region get() = requireValues().region
        override val accessKey get() = requireValues().accessKey
        override val secretKey get() = requireValues().secretKey

        override fun toString(): String = values?.toString()
            ?: "SnsConnectionDetails(endpoint=[UNINITIALIZED], region=[UNINITIALIZED], " +
            "accessKey=[REDACTED], secretKey=[REDACTED])"

        private fun requireValues(): AwsServiceConnectionValues =
            checkNotNull(values) { "SNS connection details have not been initialized" }
    }
}
