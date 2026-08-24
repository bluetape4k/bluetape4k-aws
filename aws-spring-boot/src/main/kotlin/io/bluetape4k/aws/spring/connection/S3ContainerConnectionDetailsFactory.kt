package io.bluetape4k.aws.spring.connection

import org.springframework.boot.testcontainers.service.connection.ContainerConnectionDetailsFactory
import org.springframework.boot.testcontainers.service.connection.ContainerConnectionSource
import org.testcontainers.containers.Container

internal class S3ContainerConnectionDetailsFactory :
    ContainerConnectionDetailsFactory<Container<*>, S3ConnectionDetails>(
        "s3",
        "software.amazon.awssdk.services.s3.S3Client",
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
    ): S3ConnectionDetails? {
        val container = resolveContainer(source, "s3")
        return if (isSupportedAwsEmulator(container)) S3ContainerConnectionDetails(source) else null
    }

    private class S3ContainerConnectionDetails(
        source: ContainerConnectionSource<Container<*>>,
    ): ContainerConnectionDetailsFactory.ContainerConnectionDetails<Container<*>>(source), S3ConnectionDetails {
        private var values: AwsServiceConnectionValues? = null

        override fun afterPropertiesSet() {
            values = initializeAwsServiceConnectionDetails(
                serviceName = "s3",
                initialize = { super.afterPropertiesSet() },
                snapshot = { snapshotAwsServiceConnection(getContainer(), "s3") },
            )
        }

        override val endpoint get() = requireValues().endpoint
        override val region get() = requireValues().region
        override val accessKey get() = requireValues().accessKey
        override val secretKey get() = requireValues().secretKey

        override fun toString(): String = values?.toString()
            ?: "S3ConnectionDetails(endpoint=[UNINITIALIZED], region=[UNINITIALIZED], " +
            "accessKey=[REDACTED], secretKey=[REDACTED])"

        private fun requireValues(): AwsServiceConnectionValues =
            checkNotNull(values) { "S3 connection details have not been initialized" }
    }
}
