package io.bluetape4k.aws.spring.sns

/** SNS HTTP envelope body limits shared by servlet and reactive adapters. */
internal object SnsHttpMessageLimits {
    const val MAX_BYTES: Int = 256 * 1024
    const val MAX_READ_BYTES: Int = MAX_BYTES + 1
}
