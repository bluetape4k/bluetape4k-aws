package io.bluetape4k.aws.spring.connection

import org.springframework.boot.testcontainers.service.connection.ContainerConnectionDetailsFactory
import org.springframework.boot.testcontainers.service.connection.ContainerConnectionSource
import org.testcontainers.containers.Container

internal class SqsContainerConnectionDetailsFactory :
    ContainerConnectionDetailsFactory<Container<*>, SqsConnectionDetails>(
        "sqs",
        "software.amazon.awssdk.services.sqs.SqsClient",
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
    ): SqsConnectionDetails? {
        val container = resolveContainer(source, "sqs")
        return if (isSupportedAwsEmulator(container)) SqsContainerConnectionDetails(source) else null
    }

    private class SqsContainerConnectionDetails(
        source: ContainerConnectionSource<Container<*>>,
    ): ContainerConnectionDetailsFactory.ContainerConnectionDetails<Container<*>>(source), SqsConnectionDetails {
        private var values: AwsServiceConnectionValues? = null

        override fun afterPropertiesSet() {
            values = initializeAwsServiceConnectionDetails(
                serviceName = "sqs",
                initialize = { super.afterPropertiesSet() },
                snapshot = { snapshotAwsServiceConnection(getContainer(), "sqs") },
            )
        }

        override val endpoint get() = requireValues().endpoint
        override val region get() = requireValues().region
        override val accessKey get() = requireValues().accessKey
        override val secretKey get() = requireValues().secretKey

        override fun toString(): String = values?.toString()
            ?: "SqsConnectionDetails(endpoint=[UNINITIALIZED], region=[UNINITIALIZED], " +
            "accessKey=[REDACTED], secretKey=[REDACTED])"

        private fun requireValues(): AwsServiceConnectionValues =
            checkNotNull(values) { "SQS connection details have not been initialized" }
    }
}
