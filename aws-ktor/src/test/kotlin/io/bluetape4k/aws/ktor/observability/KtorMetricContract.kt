package io.bluetape4k.aws.ktor.observability

internal object KtorMetricContract {
    const val TAG_OPERATION: String = "operation"
    const val TAG_OUTCOME: String = "outcome"
    const val TAG_QUEUE_NAME: String = "queue.name"
    const val TAG_BUCKET: String = "bucket"

    const val OPERATION_GET_OBJECT: String = "get_object"
    const val OPERATION_RECEIVE: String = "receive"

    const val OUTCOME_SUCCESS: String = "success"
}
