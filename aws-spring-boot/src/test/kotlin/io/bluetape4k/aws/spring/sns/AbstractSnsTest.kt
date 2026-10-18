package io.bluetape4k.aws.spring.sns

import io.bluetape4k.aws.spring.test.AwsSpringBootTestEmulator
import io.bluetape4k.logging.KLogging

@Suppress("UtilityClassWithPublicConstructor")
abstract class AbstractSnsTest {

    companion object: KLogging() {

        @JvmStatic
        protected val awsEmulator by lazy {
            AwsSpringBootTestEmulator.get("sns", "sqs")
        }
    }
}
