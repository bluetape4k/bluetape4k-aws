package io.bluetape4k.aws.kotlin.kinesis;

import aws.sdk.kotlin.services.kinesis.KinesisClient;
import aws.sdk.kotlin.services.kinesis.model.GetRecordsRequest;
import aws.sdk.kotlin.services.kinesis.model.GetRecordsResponse;
import aws.sdk.kotlin.services.kinesis.model.GetShardIteratorRequest;
import aws.sdk.kotlin.services.kinesis.model.GetShardIteratorResponse;
import aws.sdk.kotlin.services.kinesis.model.PutRecordRequest;
import aws.sdk.kotlin.services.kinesis.model.PutRecordResponse;
import aws.sdk.kotlin.services.kinesis.model.PutRecordsRequest;
import aws.sdk.kotlin.services.kinesis.model.PutRecordsRequestEntry;
import aws.sdk.kotlin.services.kinesis.model.PutRecordsResponse;
import aws.sdk.kotlin.services.kinesis.model.ShardIteratorType;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.jvm.functions.Function1;

/** Pre-change JVM surface used only to compile the legacy consumer fixture. */
public final class KinesisClientExtensionsKt {
    private KinesisClientExtensionsKt() {
    }

    public static Object putRecord(
            KinesisClient client,
            String streamName,
            String partitionKey,
            byte[] data,
            Function1<? super PutRecordRequest.Builder, Unit> builder,
            Continuation<? super PutRecordResponse> continuation
    ) {
        return null;
    }

    public static Object putRecord$default(
            KinesisClient client,
            String streamName,
            String partitionKey,
            byte[] data,
            Function1 builder,
            Continuation continuation,
            int mask,
            Object marker
    ) {
        return null;
    }

    public static Object putRecords(
            KinesisClient client,
            String streamName,
            java.util.List<PutRecordsRequestEntry> entries,
            Function1<? super PutRecordsRequest.Builder, Unit> builder,
            Continuation<? super PutRecordsResponse> continuation
    ) {
        return null;
    }

    public static Object putRecords$default(
            KinesisClient client,
            String streamName,
            java.util.List entries,
            Function1 builder,
            Continuation continuation,
            int mask,
            Object marker
    ) {
        return null;
    }

    public static Object getShardIterator(
            KinesisClient client,
            String streamName,
            String shardId,
            ShardIteratorType type,
            Function1<? super GetShardIteratorRequest.Builder, Unit> builder,
            Continuation<? super GetShardIteratorResponse> continuation
    ) {
        return null;
    }

    public static Object getShardIterator$default(
            KinesisClient client,
            String streamName,
            String shardId,
            ShardIteratorType type,
            Function1 builder,
            Continuation continuation,
            int mask,
            Object marker
    ) {
        return null;
    }

    public static Object getRecords(
            KinesisClient client,
            String shardIterator,
            int limit,
            Function1<? super GetRecordsRequest.Builder, Unit> builder,
            Continuation<? super GetRecordsResponse> continuation
    ) {
        return null;
    }

    public static Object getRecords$default(
            KinesisClient client,
            String shardIterator,
            int limit,
            Function1 builder,
            Continuation continuation,
            int mask,
            Object marker
    ) {
        return null;
    }
}
