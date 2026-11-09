package io.bluetape4k.aws.consumer;

import io.bluetape4k.aws.kms.model.DeleteAliasRequestKt;
import io.bluetape4k.aws.s3.model.GetObjectRequestKt;
import io.bluetape4k.aws.s3.model.HeadObjectRequestKt;
import io.bluetape4k.aws.s3.model.DeleteKt;
import io.bluetape4k.aws.s3.model.DeleteObjectRequestKt;
import io.bluetape4k.aws.sqs.model.DeleteMessageKt;
import io.bluetape4k.aws.kinesis.model.PutRecordKt;
import io.bluetape4k.aws.sqs.SqsClientExtensionsKt;
import io.bluetape4k.aws.sns.SnsBatchValidationKt;
import java.util.List;
import software.amazon.awssdk.services.kms.model.DeleteAliasRequest;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kinesis.model.PutRecordRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sns.model.PublishBatchRequest;
import software.amazon.awssdk.services.sns.model.PublishBatchRequestEntry;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

public final class LegacyAwsJavaRequestConsumer {
    private LegacyAwsJavaRequestConsumer() {}

    public static void main(String[] args) {
        DeleteMessageRequest delete = DeleteMessageKt.deleteMessageRequestOf("queue", "receipt");
        DeleteMessageBatchRequestEntry entry = DeleteMessageKt.deleteMessageBatchRequestEntryOf("id", "receipt");
        DeleteMessageBatchRequest batch = DeleteMessageKt.deleteMessageBatchRequestOf("queue", List.of(entry));
        DeleteAliasRequest alias = DeleteAliasRequestKt.deleteAliasOf("alias/name");
        GetObjectRequest get = GetObjectRequestKt.getObjectRequestOf("bucket", "key", null, null);
        HeadObjectRequest head = HeadObjectRequestKt.headObjectRequestOf("bucket", "key");
        Delete deleteManifest = DeleteKt.deleteOfObjectsArray(new ObjectIdentifier[] { ObjectIdentifier.builder().key("key").build() }, true);
        Delete collectionDelete = DeleteKt.deleteOfObjectsCollection(List.of(ObjectIdentifier.builder().key("other").build()), false);
        DeleteObjectsRequest deleteObjects = DeleteObjectRequestKt.deleteObjectsRequestOf("bucket", deleteManifest, "requester");
        Function1<PutRecordRequest.Builder, Unit> builder = requestBuilder -> Unit.INSTANCE;
        PutRecordRequest putRecord = PutRecordKt.putRecordRequestOf(
                "stream", "partition", SdkBytes.fromUtf8String("payload"), builder);
        SqsClientExtensionsKt.validateReceiveMessageCount(10);
        List<PublishBatchRequestEntry> publishEntries = List.of(
                PublishBatchRequestEntry.builder().id("id").message("message").build());
        SnsBatchValidationKt.validatePublishBatchRequest("topic", publishEntries);
        SnsBatchValidationKt.validatePublishBatchRequest(
                PublishBatchRequest.builder().topicArn("topic").publishBatchRequestEntries(publishEntries).build());
        if (!"queue".equals(delete.queueUrl()) || !"queue".equals(batch.queueUrl()) ||
                !"alias/name".equals(alias.aliasName()) || !"bucket".equals(get.bucket()) ||
                !"key".equals(head.key()) || deleteManifest.objects().size() != 1 || collectionDelete.objects().size() != 1 ||
                !"requester".equals(deleteObjects.requestPayerAsString()) || !"stream".equals(putRecord.streamName()) ||
                putRecord.data() == null) {
            throw new AssertionError("legacy AWS Java helper invocation returned an unexpected request");
        }
        System.out.println("legacy AWS Java request helpers passed");
    }
}
