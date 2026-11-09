package io.bluetape4k.aws.consumer;

import io.bluetape4k.aws.kotlin.s3tables.model.S3TablesRequestSupportKt;
import aws.sdk.kotlin.services.s3tables.model.ListNamespacesRequest;
import aws.sdk.kotlin.services.s3tables.model.ListTableBucketsRequest;
import aws.sdk.kotlin.services.s3tables.model.ListTablesRequest;
import java.util.List;

public final class S3TablesInlineEvolutionConsumer {
    private S3TablesInlineEvolutionConsumer() {
    }

    public static void main(String[] args) {
        S3TablesRequestSupportKt.requireSelector("value", "selector");
        S3TablesRequestSupportKt.requireNamespace(List.of("namespace"));
        S3TablesRequestSupportKt.validatePage(1, "page");
        expectNullPointer(() -> S3TablesRequestSupportKt.validateList(
            (ListTableBucketsRequest) null, 1, "maxBuckets"));
        expectNullPointer(() -> S3TablesRequestSupportKt.validateList(
            (ListNamespacesRequest) null, 1, "maxNamespaces"));
        expectNullPointer(() -> S3TablesRequestSupportKt.validateList(
            (ListTablesRequest) null, 1, "maxTables"));
        expectNullPointer(() -> S3TablesRequestSupportKt.requireTableLookup(null));
        System.out.println("legacy S3 Tables inline helper consumer passed");
    }

    private static void expectNullPointer(Runnable invocation) {
        try {
            invocation.run();
        } catch (NullPointerException expected) {
            return;
        }
        throw new AssertionError("expected null request to be rejected");
    }
}
