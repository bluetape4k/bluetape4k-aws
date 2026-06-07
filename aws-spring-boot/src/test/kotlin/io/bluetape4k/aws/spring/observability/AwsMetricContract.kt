package io.bluetape4k.aws.spring.observability

internal object AwsMetricContract {
    const val TAG_SERVICE: String = "service"
    const val TAG_OPERATION: String = "operation"
    const val TAG_OUTCOME: String = "outcome"
    const val TAG_QUEUE_NAME: String = "queue.name"
    const val TAG_LISTENER_ID: String = "listener.id"
    const val TAG_BUCKET: String = "bucket"
    const val TAG_ACK_ACTION: String = "ack.action"

    const val SERVICE_S3: String = "s3"

    const val OPERATION_UPLOAD: String = "upload"
    const val OPERATION_SEND: String = "send"
    const val OPERATION_ACKNOWLEDGEMENT: String = "acknowledgement"

    const val OUTCOME_SUCCESS: String = "success"
    const val ACK_ACTION_ACK: String = "ack"
}
