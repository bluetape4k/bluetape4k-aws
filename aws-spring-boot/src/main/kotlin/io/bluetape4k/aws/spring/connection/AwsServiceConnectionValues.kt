package io.bluetape4k.aws.spring.connection

import java.net.URI

/** Immutable, redacted snapshot used by AWS service connection details. */
internal class AwsServiceConnectionValues(
    override val endpoint: URI,
    override val region: String,
    override val accessKey: String,
    override val secretKey: String,
): AwsServiceConnectionDetails {

    override fun toString(): String =
        "AwsServiceConnectionValues(endpoint=$endpoint, region=$region, accessKey=[REDACTED], secretKey=[REDACTED])"
}
