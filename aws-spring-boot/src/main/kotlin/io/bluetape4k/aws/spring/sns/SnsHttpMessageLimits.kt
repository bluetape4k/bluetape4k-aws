package io.bluetape4k.aws.spring.sns

/** Servlet와 reactive adapter가 공유하는 SNS HTTP envelope 본문 크기 제한입니다. */
internal object SnsHttpMessageLimits {
    const val MAX_BYTES: Int = 256 * 1024
    const val MAX_READ_BYTES: Int = MAX_BYTES + 1
}
