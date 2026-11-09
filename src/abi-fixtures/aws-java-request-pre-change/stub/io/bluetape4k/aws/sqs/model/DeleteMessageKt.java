package io.bluetape4k.aws.sqs.model;

import java.util.Collection;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;

public final class DeleteMessageKt {
    private DeleteMessageKt() {}

    public static DeleteMessageRequest deleteMessageRequestOf(String queueUrl, String receiptHandle) {
        return DeleteMessageRequest.builder().queueUrl(queueUrl).receiptHandle(receiptHandle).build();
    }

    public static DeleteMessageBatchRequest deleteMessageBatchRequestOf(
            String queueUrl,
            Collection<DeleteMessageBatchRequestEntry> entries
    ) {
        return DeleteMessageBatchRequest.builder().queueUrl(queueUrl).entries(entries).build();
    }

    public static DeleteMessageBatchRequestEntry deleteMessageBatchRequestEntryOf(
            String id,
            String receiptHandle
    ) {
        return DeleteMessageBatchRequestEntry.builder().id(id).receiptHandle(receiptHandle).build();
    }
}
