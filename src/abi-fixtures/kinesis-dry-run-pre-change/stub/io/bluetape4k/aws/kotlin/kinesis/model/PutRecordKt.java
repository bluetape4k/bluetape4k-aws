package io.bluetape4k.aws.kotlin.kinesis.model;

import aws.sdk.kotlin.services.kinesis.model.PutRecordRequest;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/** Pre-change JVM surface used only to compile the legacy consumer fixture. */
public final class PutRecordKt {
    private PutRecordKt() {
    }

    public static PutRecordRequest putRecordRequestOf(
            String streamName,
            String partitionKey,
            byte[] data,
            Function1<? super PutRecordRequest.Builder, Unit> builder
    ) {
        return null;
    }

    public static PutRecordRequest putRecordRequestOf$default(
            String streamName,
            String partitionKey,
            byte[] data,
            Function1 builder,
            int mask,
            Object marker
    ) {
        return null;
    }
}
