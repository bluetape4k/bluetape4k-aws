package io.bluetape4k.aws.spring.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.aws.spring.env.opaqueAwsDiagnosticIdentity
import io.bluetape4k.aws.spring.env.opaqueAwsPropertySourceIdentity
import org.junit.jupiter.api.Test

class AwsConfigDataRedactionTest {

    @Test
    fun `legacy diagnostic identities are opaque and backend scoped`() {
        val identifier = "secret-${io.bluetape4k.codec.Base58.randomString(16)}"

        val identity = opaqueAwsDiagnosticIdentity("secrets-manager", identifier)

        identity shouldContain "bluetape4k.aws.configdata.secrets-manager."
        identity shouldNotContain identifier
        identity.length shouldBeEqualTo "bluetape4k.aws.configdata.secrets-manager.".length + 12
    }

    @Test
    fun `legacy property source names are hashed without changing compatibility names`() {
        val sourceName = "bluetape4k.aws.s3.config.config-${io.bluetape4k.codec.Base58.randomString(16)}"

        val identity = opaqueAwsPropertySourceIdentity(sourceName)

        identity shouldContain "bluetape4k.aws.configdata.s3."
        identity shouldNotContain sourceName
    }
}
