package io.bluetape4k.aws.s3.model;

import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;

public final class DeleteObjectRequestKt {
    private DeleteObjectRequestKt() {}

    public static DeleteObjectsRequest deleteObjectsRequestOf(
            String bucket,
            Delete delete,
            String requestPayer
    ) {
        return DeleteObjectsRequest.builder()
                .bucket(bucket)
                .delete(delete)
                .requestPayer(requestPayer)
                .build();
    }
}
