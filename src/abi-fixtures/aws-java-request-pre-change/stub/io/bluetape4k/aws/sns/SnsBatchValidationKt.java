package io.bluetape4k.aws.sns;

import java.util.List;
import software.amazon.awssdk.services.sns.model.PublishBatchRequest;

public final class SnsBatchValidationKt {
    private SnsBatchValidationKt() {}

    public static void validatePublishBatchRequest(String topicArn, List<?> entries) {
        if (topicArn == null || topicArn.isBlank() || entries == null || entries.isEmpty() || entries.size() > 10) {
            throw new IllegalArgumentException("invalid batch request");
        }
    }

    public static void validatePublishBatchRequest(PublishBatchRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
    }
}
