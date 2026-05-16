package io.bluetape4k.aws.spring.sns

/**
 * Amazon SNS SMS delivery type for the `AWS.SNS.SMS.SMSType` message attribute.
 */
enum class SnsSmsType(val attributeValue: String) {

    /**
     * Cost-optimized, non-critical messages such as marketing notifications.
     */
    PROMOTIONAL("Promotional"),

    /**
     * Reliability-optimized messages such as one-time passwords or account alerts.
     */
    TRANSACTIONAL("Transactional"),
}
