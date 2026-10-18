package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.aws.spring.test.AwsSpringBootTestEmulator
import io.bluetape4k.logging.KLogging

abstract class AbstractSqsTest {

    companion object: KLogging() {
        @JvmStatic
        protected val awsEmulator by lazy {
            AwsSpringBootTestEmulator.get("sns", "sqs")
        }
    }
}
