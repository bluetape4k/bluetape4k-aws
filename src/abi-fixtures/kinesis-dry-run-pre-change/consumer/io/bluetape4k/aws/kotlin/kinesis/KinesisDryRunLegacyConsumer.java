package io.bluetape4k.aws.kotlin.kinesis;

import aws.sdk.kotlin.services.kinesis.model.GetShardIteratorRequest;
import aws.sdk.kotlin.services.kinesis.model.PutRecordRequest;
import aws.sdk.kotlin.services.kinesis.model.ShardIteratorType;
import io.bluetape4k.aws.kotlin.kinesis.model.GetShardIteratorKt;
import io.bluetape4k.aws.kotlin.kinesis.model.PutRecordKt;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

import java.util.List;

/** Binary consumer compiled only against the frozen pre-change stubs. */
public final class KinesisDryRunLegacyConsumer {
    private static final byte[] DATA = "legacy".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final Function1<Object, Unit> NOOP = ignored -> Unit.INSTANCE;

    private KinesisDryRunLegacyConsumer() {
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void linkExtensionCalls() {
        expectNullClientFailure(() -> KinesisClientExtensionsKt.putRecord(null, "stream", "key", DATA, NOOP, null));
        expectNullClientFailure(() -> KinesisClientExtensionsKt.putRecord$default(
                null, "stream", "key", DATA, null, null, 8, null));
        expectNullClientFailure(() -> KinesisClientExtensionsKt.putRecords(null, "stream", List.of(), NOOP, null));
        expectNullClientFailure(() -> KinesisClientExtensionsKt.putRecords$default(
                null, "stream", List.of(), null, null, 4, null));
        expectNullClientFailure(() -> KinesisClientExtensionsKt.getShardIterator(
                null, "stream", "shardId-000000000000", ShardIteratorType.TrimHorizon.INSTANCE, NOOP, null));
        expectNullClientFailure(() -> KinesisClientExtensionsKt.getShardIterator$default(
                null, "stream", "shardId-000000000000", null, null, null, 12, null));
        expectNullClientFailure(() -> KinesisClientExtensionsKt.getRecords(null, "iterator", 10, NOOP, null));
        expectNullClientFailure(() -> KinesisClientExtensionsKt.getRecords$default(
                null, "iterator", 0, null, null, 6, null));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void verifyHelperCalls() {
        PutRecordRequest directPut = PutRecordKt.putRecordRequestOf("stream", "key", DATA, NOOP);
        PutRecordRequest defaultPut = PutRecordKt.putRecordRequestOf$default("stream", "key", DATA, null, 8, null);
        require("stream".equals(directPut.getStreamName()), "direct PutRecord streamName");
        require("key".equals(defaultPut.getPartitionKey()), "default PutRecord partitionKey");
        require(Boolean.FALSE.equals(directPut.getDryRun()), "legacy direct PutRecord dryRun=false");
        require(Boolean.FALSE.equals(defaultPut.getDryRun()), "legacy default PutRecord dryRun=false");

        GetShardIteratorRequest directIterator = GetShardIteratorKt.getShardIteratorRequestOf(
                "stream", "shardId-000000000000", ShardIteratorType.TrimHorizon.INSTANCE, NOOP);
        GetShardIteratorRequest defaultIterator = GetShardIteratorKt.getShardIteratorRequestOf$default(
                "stream", "shardId-000000000000", null, null, 12, null);
        require("shardId-000000000000".equals(directIterator.getShardId()), "direct iterator shardId");
        require(ShardIteratorType.TrimHorizon.INSTANCE.equals(defaultIterator.getShardIteratorType()), "default iterator type");
        require(Boolean.FALSE.equals(directIterator.getDryRun()), "legacy direct iterator dryRun=false");
        require(Boolean.FALSE.equals(defaultIterator.getDryRun()), "legacy default iterator dryRun=false");
    }

    private static void expectNullClientFailure(ThrowingCall call) {
        try {
            call.run();
            throw new AssertionError("Legacy extension call unexpectedly returned for a null client");
        } catch (NullPointerException expected) {
            // The old JVM owner/name/descriptor linked successfully before the null receiver was used.
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError("Legacy Kinesis compatibility assertion failed: " + label);
        }
    }

    public static void main(String[] args) {
        linkExtensionCalls();
        verifyHelperCalls();
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
