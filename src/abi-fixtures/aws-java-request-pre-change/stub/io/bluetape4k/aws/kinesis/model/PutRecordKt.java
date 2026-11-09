package io.bluetape4k.aws.kinesis.model;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kinesis.model.PutRecordRequest;

public final class PutRecordKt {
    private PutRecordKt() {}

    public static PutRecordRequest putRecordRequestOf(
            String streamName,
            String partitionKey,
            SdkBytes data,
            Function1<? super PutRecordRequest.Builder, Unit> builder
    ) {
        PutRecordRequest.Builder requestBuilder = PutRecordRequest.builder()
                .streamName(streamName)
                .partitionKey(partitionKey)
                .data(data);
        builder.invoke(requestBuilder);
        return requestBuilder.build();
    }
}
