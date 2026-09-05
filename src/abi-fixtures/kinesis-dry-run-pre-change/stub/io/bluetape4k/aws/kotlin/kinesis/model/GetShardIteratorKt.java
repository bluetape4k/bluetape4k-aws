package io.bluetape4k.aws.kotlin.kinesis.model;

import aws.sdk.kotlin.services.kinesis.model.GetShardIteratorRequest;
import aws.sdk.kotlin.services.kinesis.model.ShardIteratorType;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/** Pre-change JVM surface used only to compile the legacy consumer fixture. */
public final class GetShardIteratorKt {
    private GetShardIteratorKt() {
    }

    public static GetShardIteratorRequest getShardIteratorRequestOf(
            String streamName,
            String shardId,
            ShardIteratorType type,
            Function1<? super GetShardIteratorRequest.Builder, Unit> builder
    ) {
        return null;
    }

    public static GetShardIteratorRequest getShardIteratorRequestOf$default(
            String streamName,
            String shardId,
            ShardIteratorType type,
            Function1 builder,
            int mask,
            Object marker
    ) {
        return null;
    }
}
